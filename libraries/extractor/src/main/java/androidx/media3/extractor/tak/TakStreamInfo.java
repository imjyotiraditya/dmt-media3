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
package androidx.media3.extractor.tak;

import androidx.annotation.Nullable;

/** Stream information of a TAK file, which its first metadata block holds. */
/* package */ final class TakStreamInfo {

  /** Number of samples a frame holds, for the frame durations that the format defines. */
  private static final int[] FRAME_DURATION_QUANTS =
      new int[] {3, 4, 6, 8, 4096, 8192, 16384, 512, 1024, 2048};

  /** The last frame duration that is a fraction of the sample rate rather than a sample count. */
  private static final int LAST_FRACTIONAL_DURATION_TYPE = 3;

  private static final int MAX_FRACTIONAL_SAMPLE_COUNT = 16384;
  private static final int FRAME_DURATION_QUANT_SHIFT = 5;

  private static final int SAMPLE_RATE_MIN = 6000;
  private static final int BIT_DEPTH_MIN = 8;
  private static final int CHANNEL_COUNT_MIN = 1;

  private static final int ENCODER_CODEC_BITS = 6;
  private static final int ENCODER_PROFILE_BITS = 4;
  private static final int FRAME_DURATION_BITS = 4;
  private static final int SAMPLE_COUNT_BITS = 35;
  private static final int DATA_TYPE_BITS = 3;
  private static final int SAMPLE_RATE_BITS = 18;
  private static final int BIT_DEPTH_BITS = 5;
  private static final int CHANNEL_COUNT_BITS = 4;
  private static final int VALID_BITS = 5;
  private static final int CHANNEL_LAYOUT_BITS = 6;

  /** The sample rate in Hertz. */
  public final int sampleRate;

  /** Number of bits every sample holds. */
  public final int bitDepth;

  /** Number of channels in the file. */
  public final int channelCount;

  /** Number of samples in the file, or 0 if the file does not say. */
  public final long sampleCount;

  /** Number of samples every frame but the last one holds. */
  public final int frameSamples;

  /**
   * Parses the stream information that {@code reader} is positioned at, or returns null if it is
   * malformed.
   */
  @Nullable
  public static TakStreamInfo parse(TakBitReader reader) {
    reader.skipBits(ENCODER_CODEC_BITS + ENCODER_PROFILE_BITS);
    int frameDurationType = reader.readBits(FRAME_DURATION_BITS);
    long sampleCount = reader.readBitsLong(SAMPLE_COUNT_BITS);
    reader.skipBits(DATA_TYPE_BITS);
    int sampleRate = reader.readBits(SAMPLE_RATE_BITS) + SAMPLE_RATE_MIN;
    int bitDepth = reader.readBits(BIT_DEPTH_BITS) + BIT_DEPTH_MIN;
    int channelCount = reader.readBits(CHANNEL_COUNT_BITS) + CHANNEL_COUNT_MIN;
    if (reader.readBit()) {
      reader.skipBits(VALID_BITS);
      if (reader.readBit()) {
        reader.skipBits(channelCount * CHANNEL_LAYOUT_BITS);
      }
    }

    int frameSamples = frameSamples(sampleRate, frameDurationType);
    if (frameSamples <= 0) {
      return null;
    }
    return new TakStreamInfo(sampleRate, bitDepth, channelCount, sampleCount, frameSamples);
  }

  /**
   * Returns the number of samples a frame of the given duration type holds, or 0 if the duration
   * type is not one that the format defines.
   */
  private static int frameSamples(int sampleRate, int frameDurationType) {
    int frameSamples;
    int maxFrameSamples;
    if (frameDurationType <= LAST_FRACTIONAL_DURATION_TYPE) {
      frameSamples =
          (sampleRate * FRAME_DURATION_QUANTS[frameDurationType]) >> FRAME_DURATION_QUANT_SHIFT;
      maxFrameSamples = MAX_FRACTIONAL_SAMPLE_COUNT;
    } else if (frameDurationType < FRAME_DURATION_QUANTS.length) {
      frameSamples = FRAME_DURATION_QUANTS[frameDurationType];
      maxFrameSamples =
          (sampleRate * FRAME_DURATION_QUANTS[LAST_FRACTIONAL_DURATION_TYPE])
              >> FRAME_DURATION_QUANT_SHIFT;
    } else {
      return 0;
    }
    return frameSamples > maxFrameSamples ? 0 : frameSamples;
  }

  private TakStreamInfo(
      int sampleRate, int bitDepth, int channelCount, long sampleCount, int frameSamples) {
    this.sampleRate = sampleRate;
    this.bitDepth = bitDepth;
    this.channelCount = channelCount;
    this.sampleCount = sampleCount;
    this.frameSamples = frameSamples;
  }
}
