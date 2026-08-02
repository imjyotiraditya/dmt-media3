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

import androidx.annotation.Nullable;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Metadata;
import androidx.media3.common.util.UnstableApi;
import com.google.common.base.Ascii;
import com.google.common.primitives.Ints;

/** An item of an APE tag, which Monkey's Audio, WavPack, TrueAudio and TAK files carry. */
@UnstableApi
public final class ApeTagItem implements Metadata.Entry {

  /** The key in upper case, to ease case-insensitive comparisons. */
  public final String key;

  /** The value. */
  public final String value;

  /**
   * Constructs an instance.
   *
   * @param key The key. Files write these in whichever case they please, so it is upper cased.
   * @param value The value.
   */
  public ApeTagItem(String key, String value) {
    this.key = Ascii.toUpperCase(key);
    this.value = value;
  }

  @Override
  public void populateMediaMetadata(MediaMetadata.Builder builder) {
    switch (key) {
      case "TITLE":
        builder.setTitle(value);
        break;
      case "ARTIST":
        builder.setArtist(value);
        break;
      case "ALBUM":
        builder.setAlbumTitle(value);
        break;
      case "ALBUM ARTIST":
      case "ALBUMARTIST":
        builder.setAlbumArtist(value);
        break;
      case "COMPOSER":
        builder.setComposer(value);
        break;
      case "GENRE":
        builder.setGenre(value);
        break;
      case "COMMENT":
        builder.setDescription(value);
        break;
      case "TRACK":
        // The value may count the tracks of the release as well, as in "9/12".
        @Nullable Integer trackNumber = Ints.tryParse(beforeSlash(value));
        if (trackNumber != null) {
          builder.setTrackNumber(trackNumber);
        }
        @Nullable Integer totalTracks = afterSlash(value);
        if (totalTracks != null) {
          builder.setTotalTrackCount(totalTracks);
        }
        break;
      case "DISC":
        @Nullable Integer discNumber = Ints.tryParse(beforeSlash(value));
        if (discNumber != null) {
          builder.setDiscNumber(discNumber);
        }
        @Nullable Integer totalDiscs = afterSlash(value);
        if (totalDiscs != null) {
          builder.setTotalDiscCount(totalDiscs);
        }
        break;
      case "YEAR":
        // The value may be a whole date, as in "1995-04-21", of which the year is the start.
        @Nullable Integer year = Ints.tryParse(value.length() > 4 ? value.substring(0, 4) : value);
        if (year != null) {
          builder.setRecordingYear(year);
        }
        break;
      default:
        break;
    }
  }

  /** Returns the part of {@code value} before its first slash, or all of it if it holds none. */
  private static String beforeSlash(String value) {
    int slashIndex = value.indexOf('/');
    return slashIndex == -1 ? value : value.substring(0, slashIndex);
  }

  /**
   * Returns the part of {@code value} after its first slash as a number, or null if there is none.
   */
  @Nullable
  private static Integer afterSlash(String value) {
    int slashIndex = value.indexOf('/');
    return slashIndex == -1 ? null : Ints.tryParse(value.substring(slashIndex + 1));
  }

  @Override
  public String toString() {
    return "APE: " + key + "=" + value;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || getClass() != obj.getClass()) {
      return false;
    }
    ApeTagItem other = (ApeTagItem) obj;
    return key.equals(other.key) && value.equals(other.value);
  }

  @Override
  public int hashCode() {
    int result = 17;
    result = 31 * result + key.hashCode();
    result = 31 * result + value.hashCode();
    return result;
  }
}
