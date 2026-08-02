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

/**
 * Header of a TAK frame.
 *
 * <p>Frames carry no length, so they are found by looking for the sync word and checking the
 * checksum that ends the header, which is what {@link #parse} does.
 */
/* package */ final class TakFrameHeader {

  /** The sync word at the start of every frame, stored least significant byte first. */
  public static final int SYNC_WORD = 0xA0FF;

  /** The first byte of the sync word as stored, which a search for a frame looks for. */
  public static final byte SYNC_BYTE_0 = (byte) 0xFF;

  /** The second byte of the sync word as stored. */
  public static final byte SYNC_BYTE_1 = (byte) 0xA0;

  /** The largest number of bytes a frame header may hold. */
  public static final int MAX_SIZE_IN_BYTES = 24;

  /** The smallest number of bytes a frame header may hold. */
  public static final int MIN_SIZE_IN_BYTES = 8;

  private static final int FLAG_IS_LAST = 0x1;
  private static final int FLAG_HAS_INFO = 0x2;
  private static final int FLAG_HAS_METADATA = 0x4;

  private static final int SYNC_WORD_BITS = 16;
  private static final int FLAGS_BITS = 3;
  private static final int FRAME_NUMBER_BITS = 21;
  private static final int LAST_FRAME_SAMPLE_COUNT_BITS = 14;
  private static final int CRC_BITS = 24;

  /** The generator polynomial of the checksum that ends the header. */
  private static final int CRC_POLYNOMIAL = 0x864CFB;

  /** The value the checksum starts from. */
  private static final int CRC_INITIAL_VALUE = 0xCE04B7;

  /**
   * Table of the checksum of every byte value, stored least significant byte first so that the
   * checksum can be updated a byte at a time.
   */
  private static final int[] CRC_BYTES = new int[256];

  static {
    for (int i = 0; i < CRC_BYTES.length; i++) {
      int value = i << 24;
      for (int bit = 0; bit < 8; bit++) {
        value = (value << 1) ^ ((CRC_POLYNOMIAL << 8) & (value >> 31));
      }
      CRC_BYTES[i] = Integer.reverseBytes(value);
    }
  }

  /** Index of the frame in the file, which gives its timestamp. */
  public final int frameNumber;

  /** Number of samples the frame holds, or 0 if it holds as many as every other frame. */
  public final int lastFrameSamples;

  /** The stream information the frame holds, or null if it holds none. */
  @Nullable public final TakStreamInfo streamInfo;

  /**
   * Parses the frame header at {@code offset}, or returns null if there is no frame there.
   *
   * @param data The array holding the header.
   * @param offset The offset in bytes of the sync word.
   * @param limit The offset in bytes of the end of the data that may be read.
   */
  @Nullable
  public static TakFrameHeader parse(byte[] data, int offset, int limit) {
    TakBitReader reader = new TakBitReader(data, offset, limit - offset);
    if (!reader.canReadBits(SYNC_WORD_BITS + FLAGS_BITS + FRAME_NUMBER_BITS + CRC_BITS)
        || reader.readBits(SYNC_WORD_BITS) != SYNC_WORD) {
      return null;
    }

    int flags = reader.readBits(FLAGS_BITS);
    if ((flags & FLAG_HAS_METADATA) != 0) {
      return null;
    }
    int frameNumber = reader.readBits(FRAME_NUMBER_BITS);
    int lastFrameSamples = 0;
    if ((flags & FLAG_IS_LAST) != 0) {
      lastFrameSamples = reader.readBits(LAST_FRAME_SAMPLE_COUNT_BITS) + 1;
      reader.skipBits(2);
    }
    @Nullable TakStreamInfo streamInfo = null;
    if ((flags & FLAG_HAS_INFO) != 0) {
      streamInfo = TakStreamInfo.parse(reader);
      if (streamInfo == null) {
        return null;
      }
      if (reader.readBits(6) != 0) {
        reader.skipBits(25);
      }
      reader.byteAlign();
    }

    if (!reader.canReadBits(CRC_BITS)) {
      return null;
    }
    int sizeInBytes = reader.getPosition() / 8 - offset + CRC_BITS / 8;
    if (!checkCrc(data, offset, sizeInBytes)) {
      return null;
    }
    return new TakFrameHeader(frameNumber, lastFrameSamples, streamInfo);
  }

  /** Returns whether the checksum that ends the header matches the bytes before it. */
  private static boolean checkCrc(byte[] data, int offset, int sizeInBytes) {
    int checksummedSize = sizeInBytes - CRC_BITS / 8;
    int crc = CRC_INITIAL_VALUE;
    for (int i = offset; i < offset + checksummedSize; i++) {
      crc = CRC_BYTES[(crc ^ data[i]) & 0xFF] ^ (crc >>> 8);
    }

    int expectedCrc =
        ((data[offset + checksummedSize] & 0xFF) << 16)
            | ((data[offset + checksummedSize + 1] & 0xFF) << 8)
            | (data[offset + checksummedSize + 2] & 0xFF);
    return crc == expectedCrc;
  }

  private TakFrameHeader(
      int frameNumber, int lastFrameSamples, @Nullable TakStreamInfo streamInfo) {
    this.frameNumber = frameNumber;
    this.lastFrameSamples = lastFrameSamples;
    this.streamInfo = streamInfo;
  }
}
