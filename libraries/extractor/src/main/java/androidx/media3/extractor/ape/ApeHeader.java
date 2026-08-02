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

import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.ExtractorInput;
import java.io.IOException;

/** Header of a Monkey's Audio file, as defined by the Monkey's Audio file format. */
/* package */ final class ApeHeader {

  /** The "MAC " signature at the start of every file, stored as a big endian integer. */
  public static final int SIGNATURE = 0x4D414320;

  /** Size in bytes of the signature and the file version that follows it. */
  public static final int SIGNATURE_AND_VERSION_SIZE = 6;

  /** The largest header this class reads, which is the descriptor of a modern file. */
  public static final int MAX_SIZE_IN_BYTES = 52;

  /** The first file version holding a seek table that can be used to seek. */
  private static final int MIN_FILE_VERSION = 3810;

  /** The last file version the format defines. */
  private static final int MAX_FILE_VERSION = 3990;

  /** The first file version holding a descriptor before the header. */
  private static final int DESCRIPTOR_FILE_VERSION = 3980;

  /** Size in bytes of the descriptor fields that the format defines. */
  private static final int DESCRIPTOR_SIZE = 52;

  /** Size in bytes of the header that follows the descriptor. */
  private static final int HEADER_SIZE = 24;

  /** Size in bytes of the header of a file holding no descriptor. */
  private static final int LEGACY_HEADER_SIZE = 32;

  private static final int FLAG_8_BIT = 0x0001;
  private static final int FLAG_HAS_PEAK_LEVEL = 0x0004;
  private static final int FLAG_24_BIT = 0x0008;
  private static final int FLAG_HAS_SEEK_ELEMENTS = 0x0010;
  private static final int FLAG_CREATE_WAV_HEADER = 0x0020;

  private static final int SEEK_TABLE_ENTRY_SIZE = 4;

  /** The version of the file, in thousandths. */
  public final int fileVersion;

  /** The compression level the file was written with. */
  public final int compressionType;

  /** Flags describing the format of the samples. */
  public final int formatFlags;

  /** Number of blocks every frame but the last one holds. */
  public final int blocksPerFrame;

  /** Number of blocks the last frame holds. */
  public final int finalFrameBlocks;

  /** Number of frames in the file. */
  public final int frameCount;

  /** Number of bits every sample holds. */
  public final int bitDepth;

  /** Number of channels in the file. */
  public final int channelCount;

  /** The sample rate in Hertz. */
  public final int sampleRate;

  /** Size in bytes of the seek table that follows the header. */
  public final long seekTableLength;

  /** Size in bytes of the WAVE data that follows the samples. */
  public final long wavTailLength;

  /** Position in the file of the first frame. */
  public final long firstFramePosition;

  /**
   * Reads the header at the read position of {@code input}, leaving the read position at the start
   * of the seek table.
   *
   * @param input The {@link ExtractorInput} to read from.
   * @param scratch A scratch array holding at least {@link #MAX_SIZE_IN_BYTES} bytes.
   * @param junkLength The number of bytes before the header, which the seek table counts from.
   * @throws ParserException If the header is malformed or holds a version that is not supported.
   */
  public static ApeHeader parse(ExtractorInput input, ParsableByteArray scratch, long junkLength)
      throws IOException {
    input.readFully(scratch.getData(), /* offset= */ 0, SIGNATURE_AND_VERSION_SIZE);
    scratch.setPosition(0);
    if (scratch.readInt() != SIGNATURE) {
      throw ParserException.createForMalformedContainer("Missing APE signature", /* cause= */ null);
    }
    int fileVersion = scratch.readLittleEndianUnsignedShort();
    if (fileVersion < MIN_FILE_VERSION || fileVersion > MAX_FILE_VERSION) {
      throw ParserException.createForUnsupportedContainerFeature(
          "Unsupported APE file version: " + fileVersion);
    }
    return fileVersion >= DESCRIPTOR_FILE_VERSION
        ? parseDescriptorAndHeader(input, scratch, junkLength, fileVersion)
        : parseLegacyHeader(input, scratch, junkLength, fileVersion);
  }

  /** Parses the descriptor and the header that follows it. */
  private static ApeHeader parseDescriptorAndHeader(
      ExtractorInput input, ParsableByteArray scratch, long junkLength, int fileVersion)
      throws IOException {
    input.readFully(
        scratch.getData(), /* offset= */ 0, DESCRIPTOR_SIZE - SIGNATURE_AND_VERSION_SIZE);
    scratch.setPosition(0);
    scratch.skipBytes(2); // Padding.
    long descriptorLength = scratch.readLittleEndianUnsignedInt();
    long headerLength = scratch.readLittleEndianUnsignedInt();
    long seekTableLength = scratch.readLittleEndianUnsignedInt();
    long wavHeaderLength = scratch.readLittleEndianUnsignedInt();
    scratch.skipBytes(8); // Length of the audio data, which the seek table also gives.
    long wavTailLength = scratch.readLittleEndianUnsignedInt();
    if (descriptorLength > DESCRIPTOR_SIZE) {
      input.skipFully((int) (descriptorLength - DESCRIPTOR_SIZE));
    }

    input.readFully(scratch.getData(), /* offset= */ 0, HEADER_SIZE);
    scratch.setPosition(0);
    int compressionType = scratch.readLittleEndianUnsignedShort();
    int formatFlags = scratch.readLittleEndianUnsignedShort();
    int blocksPerFrame = (int) scratch.readLittleEndianUnsignedInt();
    int finalFrameBlocks = (int) scratch.readLittleEndianUnsignedInt();
    int frameCount = (int) scratch.readLittleEndianUnsignedInt();
    int bitDepth = scratch.readLittleEndianUnsignedShort();
    int channelCount = scratch.readLittleEndianUnsignedShort();
    int sampleRate = (int) scratch.readLittleEndianUnsignedInt();

    return new ApeHeader(
        fileVersion,
        compressionType,
        formatFlags,
        blocksPerFrame,
        finalFrameBlocks,
        frameCount,
        bitDepth,
        channelCount,
        sampleRate,
        seekTableLength,
        wavTailLength,
        /* firstFramePosition= */ junkLength
            + descriptorLength
            + headerLength
            + seekTableLength
            + wavHeaderLength);
  }

  /** Parses the header of a file written before the format gained a descriptor. */
  private static ApeHeader parseLegacyHeader(
      ExtractorInput input, ParsableByteArray scratch, long junkLength, int fileVersion)
      throws IOException {
    input.readFully(
        scratch.getData(), /* offset= */ 0, LEGACY_HEADER_SIZE - SIGNATURE_AND_VERSION_SIZE);
    scratch.setPosition(0);
    int compressionType = scratch.readLittleEndianUnsignedShort();
    int formatFlags = scratch.readLittleEndianUnsignedShort();
    int channelCount = scratch.readLittleEndianUnsignedShort();
    int sampleRate = (int) scratch.readLittleEndianUnsignedInt();
    long wavHeaderLength = scratch.readLittleEndianUnsignedInt();
    long wavTailLength = scratch.readLittleEndianUnsignedInt();
    int frameCount = (int) scratch.readLittleEndianUnsignedInt();
    int finalFrameBlocks = (int) scratch.readLittleEndianUnsignedInt();

    long headerLength = LEGACY_HEADER_SIZE;
    if ((formatFlags & FLAG_HAS_PEAK_LEVEL) != 0) {
      input.skipFully(4);
      headerLength += 4;
    }
    long seekTableLength;
    if ((formatFlags & FLAG_HAS_SEEK_ELEMENTS) != 0) {
      input.readFully(scratch.getData(), /* offset= */ 0, SEEK_TABLE_ENTRY_SIZE);
      scratch.setPosition(0);
      seekTableLength = scratch.readLittleEndianUnsignedInt() * SEEK_TABLE_ENTRY_SIZE;
      headerLength += SEEK_TABLE_ENTRY_SIZE;
    } else {
      seekTableLength = (long) frameCount * SEEK_TABLE_ENTRY_SIZE;
    }
    if ((formatFlags & FLAG_CREATE_WAV_HEADER) == 0) {
      input.skipFully((int) wavHeaderLength);
    }

    return new ApeHeader(
        fileVersion,
        compressionType,
        formatFlags,
        blocksPerFrame(fileVersion, compressionType),
        finalFrameBlocks,
        frameCount,
        bitDepth(formatFlags),
        channelCount,
        sampleRate,
        seekTableLength,
        wavTailLength,
        /* firstFramePosition= */ junkLength + headerLength + seekTableLength + wavHeaderLength);
  }

  /** Returns the number of blocks a frame holds, which older files do not store. */
  private static int blocksPerFrame(int fileVersion, int compressionType) {
    if (fileVersion >= 3950) {
      return 73728 * 4;
    } else if (fileVersion >= 3900 || compressionType >= 4000) {
      return 73728;
    } else {
      return 9216;
    }
  }

  /** Returns the number of bits a sample holds, which older files store as flags. */
  private static int bitDepth(int formatFlags) {
    if ((formatFlags & FLAG_8_BIT) != 0) {
      return 8;
    } else if ((formatFlags & FLAG_24_BIT) != 0) {
      return 24;
    } else {
      return 16;
    }
  }

  private ApeHeader(
      int fileVersion,
      int compressionType,
      int formatFlags,
      int blocksPerFrame,
      int finalFrameBlocks,
      int frameCount,
      int bitDepth,
      int channelCount,
      int sampleRate,
      long seekTableLength,
      long wavTailLength,
      long firstFramePosition) {
    this.fileVersion = fileVersion;
    this.compressionType = compressionType;
    this.formatFlags = formatFlags;
    this.blocksPerFrame = blocksPerFrame;
    this.finalFrameBlocks = finalFrameBlocks;
    this.frameCount = frameCount;
    this.bitDepth = bitDepth;
    this.channelCount = channelCount;
    this.sampleRate = sampleRate;
    this.seekTableLength = seekTableLength;
    this.wavTailLength = wavTailLength;
    this.firstFramePosition = firstFramePosition;
  }
}
