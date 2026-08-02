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

/**
 * Reads bits from a byte array in the order that TAK stores them, which is least significant bit of
 * each byte first.
 *
 * <p>{@link androidx.media3.common.util.ParsableBitArray} reads the most significant bit of each
 * byte first, which is the order that most formats use.
 */
/* package */ final class TakBitReader {

  private final byte[] data;
  private final int limit;

  private int bitPosition;

  /**
   * Creates a reader for the bits of {@code data}.
   *
   * @param data The array to read from.
   * @param offset The offset in bytes of the first byte to read.
   * @param length The number of bytes to read.
   */
  public TakBitReader(byte[] data, int offset, int length) {
    this.data = data;
    bitPosition = offset * 8;
    limit = (offset + length) * 8;
  }

  /** Returns the number of bits that have been read. */
  public int getPosition() {
    return bitPosition;
  }

  /** Returns whether {@code count} more bits can be read. */
  public boolean canReadBits(int count) {
    return bitPosition + count <= limit;
  }

  /** Skips {@code count} bits. */
  public void skipBits(int count) {
    bitPosition += count;
  }

  /** Skips to the next byte boundary. */
  public void byteAlign() {
    bitPosition = (bitPosition + 7) & ~7;
  }

  /** Reads a single bit, returning whether it is set. */
  public boolean readBit() {
    return readBits(1) == 1;
  }

  /** Reads up to 32 bits, returning them as an integer. */
  public int readBits(int count) {
    return (int) readBitsLong(count);
  }

  /** Reads up to 64 bits, returning them as a long. */
  public long readBitsLong(int count) {
    long value = 0;
    for (int i = 0; i < count; i++) {
      long bit = (data[bitPosition >> 3] >> (bitPosition & 7)) & 1;
      value |= bit << i;
      bitPosition++;
    }
    return value;
  }
}
