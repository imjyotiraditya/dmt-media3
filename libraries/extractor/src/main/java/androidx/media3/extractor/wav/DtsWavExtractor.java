/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.extractor.wav;

import static androidx.media3.common.util.Assertions.checkStateNotNull;
import static androidx.media3.extractor.ts.TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR;
import static java.lang.Math.min;

import android.util.Pair;
import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.WavUtil;
import androidx.media3.extractor.ConstantBitrateSeekMap;
import androidx.media3.extractor.DtsUtil;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.ts.DtsReader;
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator;
import java.io.IOException;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Extracts data from DTS audio carried inside a WAVE container.
 *
 * <p>A DTS-CD holds a DTS bitstream in the sample data of a file that declares itself as 16-bit
 * stereo PCM, so that it can be burned to and played from a regular audio CD. Such files are
 * detected by looking for a DTS sync word at the start of the sample data, and their sample data is
 * output as a DTS track. Files holding real PCM are left to {@link WavExtractor}.
 */
@UnstableApi
public final class DtsWavExtractor implements Extractor {

  /** Factory for {@link DtsWavExtractor} instances. */
  public static final ExtractorsFactory FACTORY = () -> new Extractor[] {new DtsWavExtractor()};

  /** The maximum number of bytes to search for the second sync word when measuring a frame. */
  private static final int MAX_FRAME_SCAN_BYTES = 16 * 1024;

  /** The maximum number of chunk headers to peek when looking for the sample data. */
  private static final int MAX_PEEKED_CHUNKS = 128;

  private static final int CHUNK_HEADER_SIZE = 8;
  private static final int RIFF_HEADER_SIZE = 12;
  private static final int SYNC_WORD_SIZE = 4;
  private static final int HEADER_SCRATCH_SIZE = 5408;
  private static final int READ_BUFFER_SIZE = 4 * 1024;

  private final DtsReader reader;
  private final ParsableByteArray sampleData;

  private @MonotonicNonNull ExtractorOutput extractorOutput;
  private int frameSize;
  private long pendingTimeUs;
  private boolean startedPacket;
  private boolean startedReading;

  /** Creates a new extractor for DTS audio in a WAVE container. */
  public DtsWavExtractor() {
    reader =
        new DtsReader(
            /* language= */ null, C.ROLE_FLAG_MAIN, HEADER_SCRATCH_SIZE, MimeTypes.AUDIO_WAV);
    sampleData = new ParsableByteArray(READ_BUFFER_SIZE);
    frameSize = C.LENGTH_UNSET;
  }

  // Extractor implementation.

  @Override
  public boolean sniff(ExtractorInput input) throws IOException {
    return peekFrameSize(input) != C.LENGTH_UNSET;
  }

  @Override
  public void init(ExtractorOutput output) {
    extractorOutput = output;
    reader.createTracks(
        output, new TrackIdGenerator(/* firstTrackId= */ 0, /* trackIdIncrement= */ 1));
    output.endTracks();
  }

  @Override
  public void seek(long position, long timeUs) {
    pendingTimeUs = timeUs;
    startedPacket = false;
    reader.seek();
  }

  @Override
  public void release() {
    // Do nothing.
  }

  @Override
  public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
    if (!startedReading && !startReading(input)) {
      return RESULT_END_OF_INPUT;
    }

    int bytesRead =
        input.read(sampleData.getData(), /* offset= */ 0, min(READ_BUFFER_SIZE, frameSize));
    if (bytesRead == C.RESULT_END_OF_INPUT) {
      return RESULT_END_OF_INPUT;
    }
    sampleData.setPosition(0);
    sampleData.setLimit(bytesRead);

    if (!startedPacket) {
      reader.packetStarted(pendingTimeUs, FLAG_DATA_ALIGNMENT_INDICATOR);
      startedPacket = true;
    }
    reader.consume(sampleData);
    return RESULT_CONTINUE;
  }

  // Internal methods.

  /**
   * Reads the WAVE header, outputs a seek map and advances {@code input} to the start of the sample
   * data. Returns whether the file holds DTS audio.
   */
  private boolean startReading(ExtractorInput input) throws IOException {
    int peekedFrameSize = peekFrameSize(input);
    if (peekedFrameSize == C.LENGTH_UNSET) {
      return false;
    }

    input.resetPeekPosition();
    if (!WavHeaderReader.checkFileType(input)) {
      return false;
    }
    input.skipFully((int) (input.getPeekPosition() - input.getPosition()));
    WavHeaderReader.readRf64SampleDataSize(input);
    WavFormat wavFormat = WavHeaderReader.readFormat(input);
    Pair<Long, Long> dataBounds = WavHeaderReader.skipToSampleData(input);

    frameSize = peekedFrameSize;
    startedReading = true;
    checkStateNotNull(extractorOutput)
        .seekMap(
            new ConstantBitrateSeekMap(
                input.getLength(),
                /* firstFrameBytePosition= */ dataBounds.first,
                /* bitrate= */ wavFormat.averageBytesPerSecond * C.BITS_PER_BYTE,
                frameSize));
    return true;
  }

  /**
   * Peeks the WAVE header and the start of the sample data, returning the distance in bytes between
   * the first two DTS sync words, or {@link C#LENGTH_UNSET} if the sample data does not hold DTS
   * audio. The read position of {@code input} is left unchanged.
   */
  private static int peekFrameSize(ExtractorInput input) throws IOException {
    input.resetPeekPosition();
    if (!WavHeaderReader.checkFileType(input)) {
      return C.LENGTH_UNSET;
    }

    ParsableByteArray scratch = new ParsableByteArray(CHUNK_HEADER_SIZE);
    input.resetPeekPosition();
    input.advancePeekPosition(RIFF_HEADER_SIZE);
    for (int chunk = 0; chunk < MAX_PEEKED_CHUNKS; chunk++) {
      if (!input.peekFully(
          scratch.getData(), /* offset= */ 0, CHUNK_HEADER_SIZE, /* allowEndOfInput= */ true)) {
        return C.LENGTH_UNSET;
      }
      scratch.setPosition(0);
      int chunkId = scratch.readInt();
      long chunkSize = scratch.readLittleEndianUnsignedInt();
      if (chunkId == WavUtil.DATA_FOURCC) {
        return peekSyncWordDistance(input);
      }
      input.advancePeekPosition((int) (chunkSize + (chunkSize & 1)));
    }
    return C.LENGTH_UNSET;
  }

  /**
   * Peeks the start of the sample data, returning the distance in bytes between the first two DTS
   * sync words, or {@link C#LENGTH_UNSET} if there is no sync word at the peek position.
   */
  private static int peekSyncWordDistance(ExtractorInput input) throws IOException {
    ParsableByteArray scratch = new ParsableByteArray(MAX_FRAME_SCAN_BYTES);
    int peeked = input.peek(scratch.getData(), /* offset= */ 0, MAX_FRAME_SCAN_BYTES);
    if (peeked < SYNC_WORD_SIZE) {
      return C.LENGTH_UNSET;
    }
    scratch.setLimit(peeked);

    int syncWord = scratch.readInt();
    if (DtsUtil.getFrameType(syncWord) != DtsUtil.FRAME_TYPE_CORE) {
      return C.LENGTH_UNSET;
    }
    for (int offset = 1; offset <= peeked - SYNC_WORD_SIZE; offset++) {
      scratch.setPosition(offset);
      if (scratch.readInt() == syncWord) {
        return offset;
      }
    }
    return C.LENGTH_UNSET;
  }
}
