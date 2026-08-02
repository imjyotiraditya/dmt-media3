/*
 * Copyright (C) 2019 The Android Open Source Project
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
package androidx.media3.extractor;

import static java.lang.Math.min;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.container.OpusUtil;
import java.io.EOFException;
import java.io.IOException;
import java.math.RoundingMode;
import org.checkerframework.dataflow.qual.Pure;

/** Extractor related utility methods. */
@UnstableApi
public final class ExtractorUtil {

  /** The maximum number of bytes to search for an ID3 tag before giving up. */
  private static final int MAX_ID3_PEEK_BYTES = 128 * 1024;

  /**
   * If {@code expression} is false, throws a {@link ParserException#createForMalformedContainer
   * container malformed ParserException} with the given message. Otherwise, does nothing.
   */
  @Pure
  public static void checkContainerInput(boolean expression, @Nullable String message)
      throws ParserException {
    if (!expression) {
      throw ParserException.createForMalformedContainer(message, /* cause= */ null);
    }
  }

  /**
   * Peeks {@code length} bytes from the input peek position, or all the bytes to the end of the
   * input if there was less than {@code length} bytes left.
   *
   * <p>If an exception is thrown, there is no guarantee on the peek position.
   *
   * @param input The stream input to peek the data from.
   * @param target A target array into which data should be written.
   * @param offset The offset into the target array at which to write.
   * @param length The maximum number of bytes to peek from the input.
   * @return The number of bytes peeked.
   * @throws IOException If an error occurs peeking from the input.
   */
  public static int peekToLength(ExtractorInput input, byte[] target, int offset, int length)
      throws IOException {
    int totalBytesPeeked = 0;
    while (totalBytesPeeked < length) {
      int bytesPeeked = input.peek(target, offset + totalBytesPeeked, length - totalBytesPeeked);
      if (bytesPeeked == C.RESULT_END_OF_INPUT) {
        break;
      }
      totalBytesPeeked += bytesPeeked;
    }
    return totalBytesPeeked;
  }

  /**
   * Equivalent to {@link ExtractorInput#readFully(byte[], int, int)} except that it returns {@code
   * false} instead of throwing an {@link EOFException} if the end of input is encountered without
   * having fully satisfied the read.
   */
  public static boolean readFullyQuietly(
      ExtractorInput input, byte[] output, int offset, int length) throws IOException {
    try {
      input.readFully(output, offset, length);
    } catch (EOFException e) {
      return false;
    }
    return true;
  }

  /**
   * Equivalent to {@link ExtractorInput#skipFully(int)} except that it returns {@code false}
   * instead of throwing an {@link EOFException} if the end of input is encountered without having
   * fully satisfied the skip.
   */
  public static boolean skipFullyQuietly(ExtractorInput input, int length) throws IOException {
    try {
      input.skipFully(length);
    } catch (EOFException e) {
      return false;
    }
    return true;
  }

  /**
   * Peeks data from {@code input}, respecting {@code allowEndOfInput}. Returns true if the peek is
   * successful.
   *
   * <p>If {@code allowEndOfInput=false} then encountering the end of the input (whether before or
   * after reading some data) will throw {@link EOFException}.
   *
   * <p>If {@code allowEndOfInput=true} then encountering the end of the input (even after reading
   * some data) will return {@code false}.
   *
   * <p>This is slightly different to the behaviour of {@link ExtractorInput#peekFully(byte[], int,
   * int, boolean)}, where {@code allowEndOfInput=true} only returns false (and suppresses the
   * exception) if the end of the input is reached before reading any data.
   */
  public static boolean peekFullyQuietly(
      ExtractorInput input, byte[] output, int offset, int length, boolean allowEndOfInput)
      throws IOException {
    try {
      return input.peekFully(output, offset, length, /* allowEndOfInput= */ allowEndOfInput);
    } catch (EOFException e) {
      if (allowEndOfInput) {
        return false;
      } else {
        throw e;
      }
    }
  }

  /**
   * Appends up to {@code length} bytes of sample data from {@code input} to {@code trackOutput},
   * returning the number of bytes appended. Fewer bytes are appended only if the end of the input
   * is reached.
   *
   * @param input The {@link ExtractorInput} from which data should be read.
   * @param trackOutput The {@link TrackOutput} to which data should be appended.
   * @param length The maximum number of bytes to append.
   * @return The number of bytes appended.
   * @throws IOException If an error occurred reading from the input.
   */
  public static int appendSampleData(ExtractorInput input, TrackOutput trackOutput, int length)
      throws IOException {
    int bytesAppended = 0;
    while (bytesAppended < length) {
      int result =
          trackOutput.sampleData(input, length - bytesAppended, /* allowEndOfInput= */ true);
      if (result == C.RESULT_END_OF_INPUT) {
        break;
      }
      bytesAppended += result;
    }
    return bytesAppended;
  }

  /**
   * Skips any ID3 tags at the start of {@code input}, which many files carry, returning the
   * metadata that the first of them holds.
   *
   * @param input The {@link ExtractorInput} to read from.
   * @param id3Peeker The {@link Id3Peeker} with which to peek the tags.
   * @param skip Whether to advance the read position past the tags. Extractors pass false while
   *     sniffing, which must leave the read position unchanged.
   * @return The metadata of the first tag, or null if the input holds no tag.
   * @throws IOException If an error occurred reading from the input.
   */
  @Nullable
  public static Metadata skipId3Data(ExtractorInput input, Id3Peeker id3Peeker, boolean skip)
      throws IOException {
    @Nullable
    Metadata metadata =
        id3Peeker.peekId3Data(input, /* id3FramePredicate= */ null, MAX_ID3_PEEK_BYTES);
    if (skip) {
      input.skipFully((int) input.getPeekPosition());
    }
    return metadata;
  }

  /**
   * Returns whether {@code signature} is at the peek position of {@code input}, read as a four byte
   * big endian integer.
   *
   * @param input The {@link ExtractorInput} to peek from.
   * @param scratch A scratch array holding at least four bytes.
   * @param signature The signature to look for.
   * @return Whether the signature is at the peek position.
   * @throws IOException If an error occurred peeking from the input.
   */
  public static boolean peekSignature(
      ExtractorInput input, ParsableByteArray scratch, int signature) throws IOException {
    return peekSignature(input, scratch, signature, /* mask= */ 0xFFFFFFFF);
  }

  /**
   * Returns whether {@code signature} is at the peek position of {@code input}, read as a four byte
   * big endian integer and masked with {@code mask}, which signatures shorter than four bytes need.
   *
   * @param input The {@link ExtractorInput} to peek from.
   * @param scratch A scratch array holding at least four bytes.
   * @param signature The signature to look for.
   * @param mask The mask to apply to the peeked bytes before comparing them.
   * @return Whether the signature is at the peek position.
   * @throws IOException If an error occurred peeking from the input.
   */
  public static boolean peekSignature(
      ExtractorInput input, ParsableByteArray scratch, int signature, int mask) throws IOException {
    if (!input.peekFully(
        scratch.getData(), /* offset= */ 0, /* length= */ 4, /* allowEndOfInput= */ true)) {
      return false;
    }
    scratch.setPosition(0);
    return (scratch.readInt() & mask) == signature;
  }

  /**
   * Returns the average bitrate of {@code lengthBytes} bytes of media that last {@code durationUs},
   * or {@link Format#NO_VALUE} if either is unknown.
   *
   * @param lengthBytes The length in bytes of the media, or a negative value if unknown.
   * @param durationUs The duration in microseconds of the media, or a negative value if unknown.
   * @return The average bitrate in bits per second, or {@link Format#NO_VALUE} if unknown.
   */
  public static int getAverageBitrate(long lengthBytes, long durationUs) {
    if (lengthBytes <= 0 || durationUs <= 0) {
      return Format.NO_VALUE;
    }
    return (int)
        min(
            Util.scaleLargeValue(
                lengthBytes * C.BITS_PER_BYTE, C.MICROS_PER_SECOND, durationUs, RoundingMode.DOWN),
            Integer.MAX_VALUE);
  }

  /**
   * Returns the maximum encoded rate for samples of the given encoding.
   *
   * @param encoding A {@link C.Encoding}.
   * @return The maximum encoded rate for this encoding in bytes per second, or {@link
   *     C#RATE_UNSET_INT} if unknown.
   */
  public static int getMaximumEncodedRateBytesPerSecond(@C.Encoding int encoding) {
    switch (encoding) {
      case C.ENCODING_MP3:
        return MpegAudioUtil.MAX_RATE_BYTES_PER_SECOND;
      case C.ENCODING_AAC_LC:
        return AacUtil.AAC_LC_MAX_RATE_BYTES_PER_SECOND;
      case C.ENCODING_AAC_HE_V1:
        return AacUtil.AAC_HE_V1_MAX_RATE_BYTES_PER_SECOND;
      case C.ENCODING_AAC_HE_V2:
        return AacUtil.AAC_HE_V2_MAX_RATE_BYTES_PER_SECOND;
      case C.ENCODING_AAC_XHE:
        return AacUtil.AAC_XHE_MAX_RATE_BYTES_PER_SECOND;
      case C.ENCODING_AAC_ELD:
        return AacUtil.AAC_ELD_MAX_RATE_BYTES_PER_SECOND;
      case C.ENCODING_AC3:
        return Ac3Util.AC3_MAX_RATE_BYTES_PER_SECOND;
      case C.ENCODING_E_AC3:
      case C.ENCODING_E_AC3_JOC:
        return Ac3Util.E_AC3_MAX_RATE_BYTES_PER_SECOND;
      case C.ENCODING_AC4:
        return Ac4Util.MAX_RATE_BYTES_PER_SECOND;
      case C.ENCODING_DTS:
        return DtsUtil.DTS_MAX_RATE_BYTES_PER_SECOND;
      case C.ENCODING_DTS_HD:
      case C.ENCODING_DTS_UHD_P2:
        return DtsUtil.DTS_HD_MAX_RATE_BYTES_PER_SECOND;
      case C.ENCODING_DOLBY_TRUEHD:
        return Ac3Util.TRUEHD_MAX_RATE_BYTES_PER_SECOND;
      case C.ENCODING_OPUS:
        return OpusUtil.MAX_BYTES_PER_SECOND;
      case C.ENCODING_PCM_16BIT:
      case C.ENCODING_PCM_16BIT_BIG_ENDIAN:
      case C.ENCODING_PCM_24BIT:
      case C.ENCODING_PCM_24BIT_BIG_ENDIAN:
      case C.ENCODING_PCM_32BIT:
      case C.ENCODING_PCM_32BIT_BIG_ENDIAN:
      case C.ENCODING_PCM_8BIT:
      case C.ENCODING_PCM_FLOAT:
      case C.ENCODING_PCM_FLOAT_BIG_ENDIAN:
      case C.ENCODING_PCM_DOUBLE:
      case C.ENCODING_PCM_DOUBLE_BIG_ENDIAN:
      case C.ENCODING_AAC_ER_BSAC:
      case C.ENCODING_DSD:
      case C.ENCODING_INVALID:
      case Format.NO_VALUE:
      default:
        return C.RATE_UNSET_INT;
    }
  }

  private ExtractorUtil() {}
}
