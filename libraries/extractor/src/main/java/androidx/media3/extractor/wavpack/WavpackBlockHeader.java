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

import androidx.media3.common.util.ParsableByteArray;

/** Header of a WavPack block, as defined by the WavPack bitstream format. */
/* package */ final class WavpackBlockHeader {

  /** Size in bytes of a WavPack block header. */
  public static final int SIZE_IN_BYTES = 32;

  /** Identifier at the start of every block, stored as a big endian integer. */
  private static final int BLOCK_ID = 0x7776706B;

  private static final int FLAG_MONO = 0x0004;
  private static final int FLAG_FINAL_BLOCK = 0x1000;

  private static final int[] SAMPLE_RATES =
      new int[] {
        6000, 8000, 9600, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000, 64000, 88200,
        96000, 192000, -1
      };

  /** Size in bytes of the block body, which follows the header. */
  public final int bodySize;

  /** Total number of samples in the stream, or 0 if unknown. */
  public final long totalSamples;

  /** Index of the first sample in this block. */
  public final long blockIndex;

  /** Number of samples in this block, or 0 if the block holds no audio. */
  public final int sampleCount;

  /** The sample rate in Hertz, or {@link #SAMPLE_RATE_UNSET} if not known. */
  public final int sampleRate;

  /** The number of channels in this block. */
  public final int channelCount;

  /** Whether this block is the last one holding samples for the same block index. */
  public final boolean isFinalBlock;

  /** Returned by {@link #sampleRate} for blocks that do not declare a rate. */
  public static final int SAMPLE_RATE_UNSET = -1;

  private WavpackBlockHeader(
      int bodySize,
      long totalSamples,
      long blockIndex,
      int sampleCount,
      int sampleRate,
      int channelCount,
      boolean isFinalBlock) {
    this.bodySize = bodySize;
    this.totalSamples = totalSamples;
    this.blockIndex = blockIndex;
    this.sampleCount = sampleCount;
    this.sampleRate = sampleRate;
    this.channelCount = channelCount;
    this.isFinalBlock = isFinalBlock;
  }

  /**
   * Parses a block header.
   *
   * @param data A buffer holding {@link #SIZE_IN_BYTES} bytes at its current position.
   * @return The parsed header, or null if {@code data} does not hold a valid block header.
   */
  @androidx.annotation.Nullable
  public static WavpackBlockHeader parse(ParsableByteArray data) {
    if (data.bytesLeft() < SIZE_IN_BYTES) {
      return null;
    }
    if (data.readInt() != BLOCK_ID) {
      return null;
    }

    long blockSize = data.readLittleEndianUnsignedInt();
    if (blockSize < 24) {
      return null;
    }
    data.skipBytes(4); // version
    long totalSamples = data.readLittleEndianUnsignedInt();
    long blockIndex = data.readLittleEndianUnsignedInt();
    int sampleCount = (int) data.readLittleEndianUnsignedInt();
    int flags = data.readLittleEndianInt();
    data.skipBytes(4); // crc

    return new WavpackBlockHeader(
        /* bodySize= */ (int) (blockSize - 24),
        totalSamples,
        blockIndex,
        sampleCount,
        SAMPLE_RATES[(flags >> 23) & 0xF],
        /* channelCount= */ (flags & FLAG_MONO) != 0 ? 1 : 2,
        /* isFinalBlock= */ (flags & FLAG_FINAL_BLOCK) != 0);
  }
}
