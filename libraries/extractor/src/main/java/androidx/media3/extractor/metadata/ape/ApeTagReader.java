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
package androidx.media3.extractor.metadata.ape;

import static java.lang.Math.min;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Metadata;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.ExtractorInput;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the APE tag that Monkey's Audio, WavPack, TrueAudio and TAK files carry.
 *
 * <p>The tag sits at the end of the file, so it is read by seeking to the end rather than by
 * reading forwards like the tags of most formats. A file may hold an ID3v1 tag after it, so the
 * footer that describes it is searched for rather than assumed to be the last bytes of the file.
 */
@UnstableApi
public final class ApeTagReader {

  /**
   * The number of bytes at the end of the file to read when looking for the tag. Tags hold text, so
   * they are small unless they carry artwork, which these formats rarely do.
   */
  public static final int TAIL_SIZE_IN_BYTES = 64 * 1024;

  /** The "APETAGEX" preamble that starts the header and the footer of every tag. */
  private static final byte[] PREAMBLE = new byte[] {'A', 'P', 'E', 'T', 'A', 'G', 'E', 'X'};

  /** Size in bytes of the header and of the footer of a tag. */
  private static final int FOOTER_SIZE = 32;

  /** Size in bytes of the size and flags that start every item. */
  private static final int ITEM_HEADER_SIZE = 8;

  /** The largest number of items this reader reads, to bound the work a malformed tag causes. */
  private static final int MAX_ITEM_COUNT = 1024;

  /** The value of the item flags that says the item holds text rather than binary data. */
  private static final int ITEM_TYPE_TEXT = 0;

  /**
   * Returns the position to seek to in order to read the tag of a file of {@code length} bytes, or
   * {@link C#INDEX_UNSET} if the length is not known.
   */
  public static long getTailPosition(long length) {
    if (length == C.LENGTH_UNSET || length <= FOOTER_SIZE) {
      return C.INDEX_UNSET;
    }
    return length - min(length, TAIL_SIZE_IN_BYTES);
  }

  /**
   * Reads the end of the file at the read position of {@code input}, returning the metadata of the
   * tag it holds, or null if it holds none.
   *
   * @param input The {@link ExtractorInput} to read from, positioned at {@link #getTailPosition}.
   * @param length The length in bytes of the file.
   * @throws IOException If an error occurred reading from the input.
   */
  @Nullable
  public static Metadata read(ExtractorInput input, long length) throws IOException {
    int tailSize = (int) min(length, TAIL_SIZE_IN_BYTES);
    byte[] tail = new byte[tailSize];
    input.readFully(tail, /* offset= */ 0, tailSize);
    return parse(new ParsableByteArray(tail));
  }

  /** Returns the metadata of the tag that {@code tail} ends with, or null if it holds none. */
  @Nullable
  public static Metadata parse(ParsableByteArray tail) {
    int footerPosition = findFooter(tail.getData(), tail.limit());
    if (footerPosition == C.INDEX_UNSET) {
      return null;
    }

    tail.setPosition(footerPosition + PREAMBLE.length);
    tail.skipBytes(4); // Version.
    int tagSize = tail.readLittleEndianInt();
    int itemCount = tail.readLittleEndianInt();
    int itemsPosition = footerPosition + FOOTER_SIZE - tagSize;
    if (itemsPosition < 0 || itemCount <= 0 || itemCount > MAX_ITEM_COUNT) {
      return null;
    }

    tail.setPosition(itemsPosition);
    List<Metadata.Entry> items = new ArrayList<>();
    for (int i = 0; i < itemCount; i++) {
      if (tail.bytesLeft() < ITEM_HEADER_SIZE) {
        break;
      }
      int valueSize = tail.readLittleEndianInt();
      int itemFlags = tail.readLittleEndianInt();
      @Nullable String key = readKey(tail);
      if (key == null || valueSize < 0 || valueSize > tail.bytesLeft()) {
        break;
      }
      String value = tail.readString(valueSize, StandardCharsets.UTF_8);
      if (((itemFlags >> 1) & 3) == ITEM_TYPE_TEXT && !key.isEmpty()) {
        items.add(new ApeTagItem(key, value));
      }
    }
    return items.isEmpty() ? null : new Metadata(ImmutableList.copyOf(items));
  }

  /**
   * Returns the position of the last footer in {@code data}, or {@link C#INDEX_UNSET} if it holds
   * none. The last one is the footer of the tag, because any earlier one starts it.
   */
  private static int findFooter(byte[] data, int limit) {
    for (int position = limit - FOOTER_SIZE; position >= 0; position--) {
      if (startsWithPreamble(data, position)) {
        return position;
      }
    }
    return C.INDEX_UNSET;
  }

  /** Returns whether the preamble of a header or a footer is at {@code position}. */
  private static boolean startsWithPreamble(byte[] data, int position) {
    for (int i = 0; i < PREAMBLE.length; i++) {
      if (data[position + i] != PREAMBLE[i]) {
        return false;
      }
    }
    return true;
  }

  /** Reads the key of an item, which ends with a zero byte, or returns null if it does not end. */
  @Nullable
  private static String readKey(ParsableByteArray tail) {
    int start = tail.getPosition();
    byte[] data = tail.getData();
    for (int position = start; position < tail.limit(); position++) {
      if (data[position] == 0) {
        tail.setPosition(position + 1);
        return new String(data, start, position - start, StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  private ApeTagReader() {}
}
