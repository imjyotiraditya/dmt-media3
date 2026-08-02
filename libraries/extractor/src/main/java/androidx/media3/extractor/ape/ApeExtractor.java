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
package androidx.media3.extractor.ape;

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
import androidx.media3.common.ParserException;
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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Extracts data from Monkey's Audio (.ape) files.
 *
 * <p>An APE file holds a header, a seek table listing the position of every frame, and the frames
 * themselves. Every frame but the last holds the same number of blocks, so the seek table is enough
 * to seek to an exact position.
 *
 * <p>The decoder needs to be told how many blocks a frame holds and how far into its first byte the
 * frame starts, so every sample is output with an eight byte prefix carrying those two values.
 */
@UnstableApi
public final class ApeExtractor implements Extractor {

  /** Factory for {@link ApeExtractor} instances. */
  public static final ExtractorsFactory FACTORY = () -> new Extractor[] {new ApeExtractor()};

  /** The size in bytes of the prefix that every sample carries for the decoder. */
  private static final int SAMPLE_PREFIX_SIZE = 8;

  /** The size in bytes of the codec specific data that the decoder needs. */
  private static final int EXTRA_DATA_SIZE = 6;

  /** The maximum number of frames a file may hold, to bound the size of the seek table. */
  private static final int MAX_FRAME_COUNT = 1024 * 1024;

  /** The size assumed for a block of the last frame when the length of the file is unknown. */
  private static final int FALLBACK_BYTES_PER_BLOCK = 8;

  private static final int SEEK_TABLE_ENTRY_SIZE = 4;

  private final Id3Peeker id3Peeker;
  private final ParsableByteArray scratch;
  private final ParsableByteArray samplePrefix;

  private @MonotonicNonNull ExtractorOutput extractorOutput;
  private @MonotonicNonNull TrackOutput trackOutput;
  private @MonotonicNonNull ApeHeader header;
  private long @MonotonicNonNull [] framePositions;
  private int @MonotonicNonNull [] frameSizes;
  private int @MonotonicNonNull [] frameSkips;
  private int frameIndex;
  private long positionBeforeApeTag;
  private @Nullable Metadata apeMetadata;
  private boolean readApeTag;
  private boolean apeTagPending;
  private boolean startedReading;

  /** Creates a new extractor for Monkey's Audio files. */
  public ApeExtractor() {
    id3Peeker = new Id3Peeker();
    scratch = new ParsableByteArray(ApeHeader.MAX_SIZE_IN_BYTES);
    samplePrefix = new ParsableByteArray(SAMPLE_PREFIX_SIZE);
  }

  // Extractor implementation.

  @Override
  public boolean sniff(ExtractorInput input) throws IOException {
    skipId3Data(input, id3Peeker, /* skip= */ false);
    return peekSignature(input, scratch, ApeHeader.SIGNATURE);
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

    // Frames may start part way into the last byte of the frame before them, so the input is not
    // always left at the start of the next frame.
    long framePosition = castNonNull(framePositions)[frameIndex];
    if (input.getPosition() != framePosition) {
      seekPosition.position = framePosition;
      return RESULT_SEEK;
    }

    ApeHeader header = castNonNull(this.header);
    int frameBlocks =
        frameIndex == frameSizes.length - 1 ? header.finalFrameBlocks : header.blocksPerFrame;
    ByteBuffer.wrap(samplePrefix.getData())
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(frameBlocks)
        .putInt(castNonNull(frameSkips)[frameIndex]);
    samplePrefix.setPosition(0);
    castNonNull(trackOutput).sampleData(samplePrefix, SAMPLE_PREFIX_SIZE);

    int bytesAppended = appendSampleData(input, trackOutput, frameSizes[frameIndex]);
    if (bytesAppended == 0) {
      return RESULT_END_OF_INPUT;
    }
    trackOutput.sampleMetadata(
        /* timeUs= */ sampleTimeUs(frameIndex),
        C.BUFFER_FLAG_KEY_FRAME,
        SAMPLE_PREFIX_SIZE + bytesAppended,
        /* offset= */ 0,
        /* cryptoData= */ null);
    frameIndex++;
    return RESULT_CONTINUE;
  }

  // Internal methods.

  /** Reads the header and the seek table, and outputs the track format and a seek map. */
  private void startReading(ExtractorInput input) throws IOException {
    @Nullable Metadata id3Metadata = skipId3Data(input, id3Peeker, /* skip= */ true);
    long junkLength = input.getPosition();
    header = ApeHeader.parse(input, scratch, junkLength);
    readSeekTable(input, header, junkLength);
    outputFormat(input, header, id3Metadata);
    startedReading = true;
  }

  /**
   * Reads the seek table, and works out the position and size of every frame in the file. Frames
   * are aligned to four bytes for the decoder, which reads them a word at a time.
   */
  private void readSeekTable(ExtractorInput input, ApeHeader header, long junkLength)
      throws IOException {
    int frameCount = header.frameCount;
    if (frameCount <= 0 || frameCount > MAX_FRAME_COUNT) {
      throw ParserException.createForMalformedContainer(
          "Invalid APE frame count: " + frameCount, /* cause= */ null);
    }
    if (header.seekTableLength / SEEK_TABLE_ENTRY_SIZE < frameCount) {
      throw ParserException.createForMalformedContainer(
          "APE seek table holds fewer entries than frames", /* cause= */ null);
    }

    ParsableByteArray seekTableBytes = new ParsableByteArray(frameCount * SEEK_TABLE_ENTRY_SIZE);
    input.readFully(seekTableBytes.getData(), /* offset= */ 0, seekTableBytes.capacity());

    framePositions = new long[frameCount];
    frameSizes = new int[frameCount];
    frameSkips = new int[frameCount];
    framePositions[0] = header.firstFramePosition;
    seekTableBytes.skipBytes(SEEK_TABLE_ENTRY_SIZE);
    for (int i = 1; i < frameCount; i++) {
      framePositions[i] = seekTableBytes.readLittleEndianUnsignedInt() + junkLength;
      frameSizes[i - 1] = (int) (framePositions[i] - framePositions[i - 1]);
      frameSkips[i] = (int) ((framePositions[i] - framePositions[0]) & 3);
    }

    long length = input.getLength();
    long finalFrameSize = 0;
    if (length != C.LENGTH_UNSET) {
      finalFrameSize = length - framePositions[frameCount - 1] - header.wavTailLength;
      finalFrameSize -= finalFrameSize & 3;
    }
    if (finalFrameSize <= 0) {
      finalFrameSize = (long) header.finalFrameBlocks * FALLBACK_BYTES_PER_BLOCK;
    }
    frameSizes[frameCount - 1] = (int) finalFrameSize;

    for (int i = 0; i < frameCount; i++) {
      framePositions[i] -= frameSkips[i];
      frameSizes[i] = (frameSizes[i] + frameSkips[i] + 3) & ~3;
    }
  }

  /** Outputs the track format and a seek map built from the seek table. */
  private void outputFormat(
      ExtractorInput input, ApeHeader header, @Nullable Metadata id3Metadata) {
    int frameCount = header.frameCount;
    long blockCount = (long) (frameCount - 1) * header.blocksPerFrame + header.finalFrameBlocks;
    long durationUs = Util.scaleLargeTimestamp(blockCount, C.MICROS_PER_SECOND, header.sampleRate);
    long[] sampleTimesUs = new long[frameCount];
    for (int i = 0; i < frameCount; i++) {
      sampleTimesUs[i] = sampleTimeUs(i);
    }

    byte[] extraData = new byte[EXTRA_DATA_SIZE];
    ByteBuffer.wrap(extraData)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putShort((short) header.fileVersion)
        .putShort((short) header.compressionType)
        .putShort((short) header.formatFlags);

    checkStateNotNull(extractorOutput)
        .seekMap(new IndexSeekMap(castNonNull(framePositions), sampleTimesUs, durationUs));
    castNonNull(trackOutput)
        .format(
            new Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_APE)
                .setChannelCount(header.channelCount)
                .setSampleRate(header.sampleRate)
                .setPcmEncoding(Util.getPcmEncoding(header.bitDepth))
                .setAverageBitrate(
                    getAverageBitrate(input.getLength() - header.firstFramePosition, durationUs))
                .setInitializationData(ImmutableList.of(extraData))
                .setMetadata(mergedMetadata(id3Metadata))
                .build());
  }

  /** Returns the timestamp of the frame at {@code frameIndex}. */
  private long sampleTimeUs(int frameIndex) {
    ApeHeader header = castNonNull(this.header);
    return Util.scaleLargeTimestamp(
        (long) frameIndex * header.blocksPerFrame, C.MICROS_PER_SECOND, header.sampleRate);
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
