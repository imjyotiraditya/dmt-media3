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
package androidx.media3.extractor.tta;

import static androidx.media3.common.util.Assertions.checkStateNotNull;
import static androidx.media3.common.util.Util.castNonNull;
import static androidx.media3.extractor.ExtractorUtil.appendSampleData;
import static androidx.media3.extractor.ExtractorUtil.getAverageBitrate;
import static androidx.media3.extractor.ExtractorUtil.peekSignature;
import static androidx.media3.extractor.ExtractorUtil.skipId3Data;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.Id3Peeker;
import androidx.media3.extractor.IndexSeekMap;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.metadata.ape.ApeTagReader;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Extracts data from TrueAudio (.tta) files.
 *
 * <p>A TTA file holds a fixed size header, a seek table listing the size of every frame, and the
 * frames themselves. Each frame holds the same number of samples, so the seek table is enough to
 * seek to an exact position.
 */
@UnstableApi
public final class TtaExtractor implements Extractor {

  /** Factory for {@link TtaExtractor} instances. */
  public static final ExtractorsFactory FACTORY = () -> new Extractor[] {new TtaExtractor()};

  private static final int SEEK_TABLE_ENTRY_SIZE = 4;
  private static final int CHECKSUM_SIZE = 4;

  private final Id3Peeker id3Peeker;
  private final ParsableByteArray scratch;

  private @MonotonicNonNull ExtractorOutput extractorOutput;
  private @MonotonicNonNull TrackOutput trackOutput;
  private @MonotonicNonNull TtaHeader header;
  private long @MonotonicNonNull [] framePositions;
  private int @MonotonicNonNull [] frameSizes;
  private int frameIndex;
  private long positionBeforeApeTag;
  private @Nullable Metadata apeMetadata;
  private boolean readApeTag;
  private boolean apeTagPending;
  private boolean startedReading;

  /** Creates a new extractor for TrueAudio files. */
  public TtaExtractor() {
    id3Peeker = new Id3Peeker();
    scratch = new ParsableByteArray(TtaHeader.SIZE_IN_BYTES);
  }

  // Extractor implementation.

  @Override
  public boolean sniff(ExtractorInput input) throws IOException {
    skipId3Data(input, id3Peeker, /* skip= */ false);
    return peekSignature(input, scratch, TtaHeader.SIGNATURE);
  }

  @Override
  public void init(ExtractorOutput output) {
    extractorOutput = output;
    trackOutput = output.track(/* id= */ 0, C.TRACK_TYPE_AUDIO);
    output.endTracks();
  }

  @Override
  public void seek(long position, long timeUs) {
    if (framePositions == null) {
      return;
    }
    frameIndex =
        Util.binarySearchFloor(
            framePositions, position, /* inclusive= */ true, /* stayInBounds= */ true);
  }

  @Override
  public void release() {
    // Do nothing.
  }

  @Override
  public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
    if (!readApeTag) {
      // The tag is at the end of the file, so it is read before the format is output.
      readApeTag = true;
      long tailPosition = ApeTagReader.getTailPosition(input.getLength());
      if (tailPosition != C.INDEX_UNSET) {
        apeTagPending = true;
        positionBeforeApeTag = input.getPosition();
        seekPosition.position = tailPosition;
        return RESULT_SEEK;
      }
    }
    if (apeTagPending) {
      apeTagPending = false;
      apeMetadata = ApeTagReader.read(input, input.getLength());
      seekPosition.position = positionBeforeApeTag;
      return RESULT_SEEK;
    }
    if (!startedReading) {
      startReading(input);
    }
    if (frameIndex >= castNonNull(frameSizes).length) {
      return RESULT_END_OF_INPUT;
    }

    int frameSize = frameSizes[frameIndex];
    if (appendSampleData(input, castNonNull(trackOutput), frameSize) < frameSize) {
      return RESULT_END_OF_INPUT;
    }
    trackOutput.sampleMetadata(
        /* timeUs= */ sampleTimeUs(frameIndex),
        C.BUFFER_FLAG_KEY_FRAME,
        frameSize,
        /* offset= */ 0,
        /* cryptoData= */ null);
    frameIndex++;
    return RESULT_CONTINUE;
  }

  // Internal methods.

  /** Reads the header and the seek table, and outputs the track format and a seek map. */
  private void startReading(ExtractorInput input) throws IOException {
    @Nullable Metadata id3Metadata = skipId3Data(input, id3Peeker, /* skip= */ true);
    header = TtaHeader.parse(input, scratch);
    readSeekTable(input, header);
    outputFormat(input, header, id3Metadata);
    startedReading = true;
  }

  /** Reads the seek table, which holds the size in bytes of every frame in the file. */
  private void readSeekTable(ExtractorInput input, TtaHeader header) throws IOException {
    ParsableByteArray seekTableBytes =
        new ParsableByteArray(header.frameCount * SEEK_TABLE_ENTRY_SIZE + CHECKSUM_SIZE);
    input.readFully(seekTableBytes.getData(), /* offset= */ 0, seekTableBytes.capacity());

    framePositions = new long[header.frameCount];
    frameSizes = new int[header.frameCount];
    long framePosition = input.getPosition();
    for (int i = 0; i < header.frameCount; i++) {
      framePositions[i] = framePosition;
      frameSizes[i] = (int) seekTableBytes.readLittleEndianUnsignedInt();
      framePosition += frameSizes[i];
    }
  }

  /** Outputs the track format and a seek map built from the seek table. */
  private void outputFormat(
      ExtractorInput input, TtaHeader header, @Nullable Metadata id3Metadata) {
    long durationUs =
        Util.scaleLargeTimestamp(header.sampleCount, C.MICROS_PER_SECOND, header.sampleRate);
    long[] sampleTimesUs = new long[header.frameCount];
    for (int i = 0; i < sampleTimesUs.length; i++) {
      sampleTimesUs[i] = sampleTimeUs(i);
    }

    checkStateNotNull(extractorOutput)
        .seekMap(new IndexSeekMap(castNonNull(framePositions), sampleTimesUs, durationUs));
    castNonNull(trackOutput)
        .format(
            new Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_TTA)
                .setChannelCount(header.channelCount)
                .setSampleRate(header.sampleRate)
                .setPcmEncoding(Util.getPcmEncoding(header.bitDepth))
                .setAverageBitrate(
                    getAverageBitrate(
                        input.getLength() - castNonNull(framePositions)[0], durationUs))
                .setInitializationData(ImmutableList.of(header.headerBytes))
                .setMetadata(mergedMetadata(id3Metadata))
                .build());
  }

  /** Returns the timestamp of the frame at {@code frameIndex}. */
  private long sampleTimeUs(int frameIndex) {
    TtaHeader header = castNonNull(this.header);
    return Util.scaleLargeTimestamp(
        (long) frameIndex * header.frameLength, C.MICROS_PER_SECOND, header.sampleRate);
  }

  /**
   * Returns the metadata of the ID3 tag at the start of the file and of the APE tag at the end of
   * it, or null if the file holds neither.
   */
  @Nullable
  private Metadata mergedMetadata(@Nullable Metadata id3Metadata) {
    if (id3Metadata == null) {
      return apeMetadata;
    }
    return apeMetadata == null ? id3Metadata : id3Metadata.copyWithAppendedEntriesFrom(apeMetadata);
  }
}
