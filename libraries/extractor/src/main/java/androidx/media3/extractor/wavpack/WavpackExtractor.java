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
package androidx.media3.extractor.wavpack;

import static androidx.media3.common.util.Assertions.checkStateNotNull;
import static androidx.media3.common.util.Util.castNonNull;
import static androidx.media3.extractor.ExtractorUtil.appendSampleData;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.ConstantBitrateSeekMap;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.metadata.ape.ApeTagReader;
import java.io.IOException;
import java.math.RoundingMode;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Extracts data from WavPack (.wv) files.
 *
 * <p>A WavPack file is a sequence of self describing blocks. Blocks that share a block index belong
 * to the same set of samples, and are output as a single sample so that they reach the decoder
 * together.
 */
@UnstableApi
public final class WavpackExtractor implements Extractor {

  /** Factory for {@link WavpackExtractor} instances. */
  public static final ExtractorsFactory FACTORY = () -> new Extractor[] {new WavpackExtractor()};

  private final ParsableByteArray headerBytes;

  private @MonotonicNonNull ExtractorOutput extractorOutput;
  private @MonotonicNonNull TrackOutput trackOutput;
  private long firstBlockPosition;
  private long positionBeforeApeTag;
  private @Nullable Metadata apeMetadata;
  private boolean readApeTag;
  private boolean apeTagPending;
  private long durationUs;
  private int sampleRate;
  private boolean outputFormat;

  /** Creates a new extractor for WavPack files. */
  public WavpackExtractor() {
    headerBytes = new ParsableByteArray(WavpackBlockHeader.SIZE_IN_BYTES);
    firstBlockPosition = C.INDEX_UNSET;
    durationUs = C.TIME_UNSET;
    sampleRate = C.RATE_UNSET_INT;
  }

  // Extractor implementation.

  @Override
  public boolean sniff(ExtractorInput input) throws IOException {
    return peekBlockHeader(input) != null;
  }

  @Override
  public void init(ExtractorOutput output) {
    extractorOutput = output;
    trackOutput = output.track(/* id= */ 0, C.TRACK_TYPE_AUDIO);
    output.endTracks();
  }

  @Override
  public void seek(long position, long timeUs) {
    // Do nothing. Blocks carry their own timestamps.
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

    WavpackBlockHeader header = peekBlockHeader(input);
    if (header == null) {
      return RESULT_END_OF_INPUT;
    }
    if (!outputFormat) {
      outputFormat(input, header);
    }

    int sampleSize = 0;
    boolean isFinalBlock = false;
    while (!isFinalBlock) {
      WavpackBlockHeader blockHeader = peekBlockHeader(input);
      if (blockHeader == null) {
        break;
      }
      int blockSize = WavpackBlockHeader.SIZE_IN_BYTES + blockHeader.bodySize;
      int blockBytesRead = appendSampleData(input, castNonNull(trackOutput), blockSize);
      sampleSize += blockBytesRead;
      if (blockBytesRead < blockSize) {
        break;
      }
      isFinalBlock = blockHeader.isFinalBlock;
    }
    if (sampleSize == 0) {
      return RESULT_END_OF_INPUT;
    }

    castNonNull(trackOutput)
        .sampleMetadata(
            /* timeUs= */ Util.scaleLargeTimestamp(
                header.blockIndex, C.MICROS_PER_SECOND, sampleRate),
            C.BUFFER_FLAG_KEY_FRAME,
            sampleSize,
            /* offset= */ 0,
            /* cryptoData= */ null);
    return RESULT_CONTINUE;
  }

  // Internal methods.

  /** Outputs the track format and a seek map, using the first block of the file. */
  private void outputFormat(ExtractorInput input, WavpackBlockHeader header) {
    sampleRate = header.sampleRate;
    firstBlockPosition = input.getPosition();
    if (header.totalSamples > 0 && sampleRate > 0) {
      durationUs = Util.scaleLargeTimestamp(header.totalSamples, C.MICROS_PER_SECOND, sampleRate);
    }

    castNonNull(trackOutput)
        .format(
            new Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_WAVPACK)
                .setChannelCount(header.channelCount)
                .setSampleRate(sampleRate)
                .setPcmEncoding(Util.getPcmEncoding(header.bitDepth))
                .setMetadata(apeMetadata)
                .build());
    checkStateNotNull(extractorOutput).seekMap(createSeekMap(input, header));
    outputFormat = true;
  }

  /**
   * Returns a seek map for the file. Blocks hold a variable number of bytes, so seeking is
   * approximated from the average bitrate of the file.
   */
  private SeekMap createSeekMap(ExtractorInput input, WavpackBlockHeader header) {
    long length = input.getLength();
    if (length == C.LENGTH_UNSET || durationUs == C.TIME_UNSET) {
      return new SeekMap.Unseekable(durationUs);
    }
    int bitrate =
        (int)
            Util.scaleLargeValue(
                (length - firstBlockPosition) * C.BITS_PER_BYTE,
                C.MICROS_PER_SECOND,
                durationUs,
                RoundingMode.DOWN);
    return new ConstantBitrateSeekMap(
        length,
        firstBlockPosition,
        bitrate,
        /* frameSize= */ WavpackBlockHeader.SIZE_IN_BYTES + header.bodySize);
  }

  /**
   * Peeks the header of the block at the read position of {@code input}, or returns null if there
   * is no block there.
   */
  @Nullable
  private WavpackBlockHeader peekBlockHeader(ExtractorInput input) throws IOException {
    input.resetPeekPosition();
    if (!input.peekFully(
        headerBytes.getData(),
        /* offset= */ 0,
        WavpackBlockHeader.SIZE_IN_BYTES,
        /* allowEndOfInput= */ true)) {
      return null;
    }
    headerBytes.setPosition(0);
    return WavpackBlockHeader.parse(headerBytes);
  }
}
