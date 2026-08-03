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

import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.ExtractorInput;
import java.io.IOException;
import java.util.Arrays;

/** Header of a TrueAudio file, as defined by the TrueAudio file format. */
/* package */ final class TtaHeader {

  /** The "TTA1" signature at the start of every file, stored as a big endian integer. */
  public static final int SIGNATURE = 0x54544131;

  /** Size in bytes of the header, including its signature and checksum. */
  public static final int SIZE_IN_BYTES = 22;

  /** The duration in seconds of a frame, as defined by the format. */
  private static final double FRAME_DURATION_SECONDS = 1.04489795918367346939;

  /** The maximum number of frames a file may hold, to bound the size of the seek table. */
  private static final int MAX_FRAME_COUNT = 1024 * 1024;

  /** Number of channels in the file. */
  public final int channelCount;

  /** The sample rate in Hertz. */
  public final int sampleRate;

  /** Number of bits per sample of the audio the file holds. */
  public final int bitDepth;

  /** Number of samples in the file. */
  public final long sampleCount;

  /** Number of samples every frame but the last one holds. */
  public final int frameLength;

  /** Number of frames in the file. */
  public final int frameCount;

  /** The bytes of the header, which the decoder needs as initialization data. */
  public final byte[] headerBytes;

  /**
   * Reads the header at the read position of {@code input}, leaving the read position at the start
   * of the seek table.
   *
   * @param input The {@link ExtractorInput} to read from.
   * @param scratch A scratch array holding at least {@link #SIZE_IN_BYTES} bytes.
   * @throws ParserException If the header is malformed.
   */
  public static TtaHeader parse(ExtractorInput input, ParsableByteArray scratch)
      throws IOException {
    input.readFully(scratch.getData(), /* offset= */ 0, SIZE_IN_BYTES);
    scratch.setPosition(0);
    if (scratch.readInt() != SIGNATURE) {
      throw ParserException.createForMalformedContainer("Missing TTA signature", /* cause= */ null);
    }
    scratch.skipBytes(2); // Audio format.
    int channelCount = scratch.readLittleEndianUnsignedShort();
    int bitDepth = scratch.readLittleEndianUnsignedShort();
    int sampleRate = (int) scratch.readLittleEndianUnsignedInt();
    long sampleCount = scratch.readLittleEndianUnsignedInt();
    if (sampleRate <= 0 || sampleCount <= 0) {
      throw ParserException.createForMalformedContainer("Invalid TTA header", /* cause= */ null);
    }

    int frameLength = (int) (FRAME_DURATION_SECONDS * sampleRate);
    long frameCount = Util.ceilDivide(sampleCount, frameLength);
    if (frameCount > MAX_FRAME_COUNT) {
      throw ParserException.createForMalformedContainer(
          "Too many TTA frames: " + frameCount, /* cause= */ null);
    }
    return new TtaHeader(
        channelCount,
        sampleRate,
        bitDepth,
        sampleCount,
        frameLength,
        (int) frameCount,
        Arrays.copyOf(scratch.getData(), SIZE_IN_BYTES));
  }

  private TtaHeader(
      int channelCount,
      int sampleRate,
      int bitDepth,
      long sampleCount,
      int frameLength,
      int frameCount,
      byte[] headerBytes) {
    this.channelCount = channelCount;
    this.sampleRate = sampleRate;
    this.bitDepth = bitDepth;
    this.sampleCount = sampleCount;
    this.frameLength = frameLength;
    this.frameCount = frameCount;
    this.headerBytes = headerBytes;
  }
}
