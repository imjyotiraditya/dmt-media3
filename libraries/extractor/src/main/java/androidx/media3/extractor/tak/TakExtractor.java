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

import static androidx.media3.common.util.Assertions.checkStateNotNull;
import static androidx.media3.common.util.Util.castNonNull;
import static androidx.media3.extractor.ExtractorUtil.appendSampleData;
import static androidx.media3.extractor.ExtractorUtil.getAverageBitrate;
import static androidx.media3.extractor.ExtractorUtil.peekSignature;
import static androidx.media3.extractor.ExtractorUtil.skipId3Data;
import static java.lang.Math.min;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.ConstantBitrateSeekMap;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.Id3Peeker;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.metadata.ape.ApeTagReader;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Extracts data from TAK (.tak) files.
 *
 * <p>A TAK file holds a chain of metadata blocks, the first of which describes the stream, followed
 * by the frames. Frames carry no length, so each one is read up to the start of the frame after it.
 */
@UnstableApi
public final class TakExtractor implements Extractor {

  /** Factory for {@link TakExtractor} instances. */
  public static final ExtractorsFactory FACTORY = () -> new Extractor[] {new TakExtractor()};

  /** The "tBaK" file signature, as a big endian integer. */
  private static final int FILE_SIGNATURE = 0x7442614B;

  /** The largest frame this extractor reads, which bounds how far it looks for the next frame. */
  private static final int MAX_FRAME_SIZE = 128 * 1024;

  /** How much more of the input to peek at a time when looking for the end of a frame. */
  private static final int SCAN_INCREMENT = 16 * 1024;

  private static final int METADATA_TYPE_END = 0;
  private static final int METADATA_TYPE_STREAM_INFO = 1;
  private static final int METADATA_TYPE_LAST_FRAME = 7;

  /** Size in bytes of the type and length that start a metadata block. */
  private static final int METADATA_HEADER_SIZE = 4;

  /** Size in bytes of the checksum that ends a metadata block. */
  private static final int METADATA_CHECKSUM_SIZE = 3;

  /** Size in bytes of the body of a block holding the position and size of the last frame. */
  private static final int LAST_FRAME_BODY_SIZE = 8;

  private static final int LAST_FRAME_POSITION_BITS = 40;
  private static final int LAST_FRAME_SIZE_BITS = 24;

  private final Id3Peeker id3Peeker;
  private final ParsableByteArray scratch;
  private final byte[] scanBuffer;

  private @MonotonicNonNull ExtractorOutput extractorOutput;
  private @MonotonicNonNull TrackOutput trackOutput;
  private @MonotonicNonNull TakStreamInfo streamInfo;
  private byte @MonotonicNonNull [] streamInfoBytes;
  private long firstFramePosition;
  private long dataEndPosition;
  private long positionBeforeApeTag;
  private @Nullable Metadata apeMetadata;
  private boolean readApeTag;
  private boolean apeTagPending;
  private boolean startedReading;

  /** Creates a new extractor for TAK files. */
  public TakExtractor() {
    id3Peeker = new Id3Peeker();
    scratch = new ParsableByteArray(TakFrameHeader.MAX_SIZE_IN_BYTES);
    scanBuffer = new byte[MAX_FRAME_SIZE];
    dataEndPosition = C.INDEX_UNSET;
  }

  // Extractor implementation.

  @Override
  public boolean sniff(ExtractorInput input) throws IOException {
    skipId3Data(input, id3Peeker, /* skip= */ false);
    return peekSignature(input, scratch, FILE_SIGNATURE);
  }

  @Override
  public void init(ExtractorOutput output) {
    extractorOutput = output;
    trackOutput = output.track(/* id= */ 0, C.TRACK_TYPE_AUDIO);
    output.endTracks();
  }

  @Override
  public void seek(long position, long timeUs) {
    // Do nothing. Reading resumes at the first frame that follows the new position.
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
    if (!startedReading) {
      startReading(input);
    }
    if (dataEndPosition != C.INDEX_UNSET && input.getPosition() >= dataEndPosition) {
      return RESULT_END_OF_INPUT;
    }

    int peeked = peekToScanBuffer(input, /* length= */ SCAN_INCREMENT);
    if (peeked < TakFrameHeader.MIN_SIZE_IN_BYTES) {
      return RESULT_END_OF_INPUT;
    }
    @Nullable
    TakFrameHeader frameHeader = TakFrameHeader.parse(scanBuffer, /* offset= */ 0, peeked);
    if (frameHeader == null) {
      // The read position is not at a frame, which happens after seeking.
      int frameOffset = findFrame(peeked, /* startOffset= */ 1);
      if (frameOffset == C.INDEX_UNSET) {
        return RESULT_END_OF_INPUT;
      }
      input.skipFully(frameOffset);
      return RESULT_CONTINUE;
    }

    // Frames carry no length, so this one ends where the next one starts. Only as much of the
    // input as it takes to find that is peeked, because frames are usually a few kilobytes.
    int frameSize = C.INDEX_UNSET;
    int searchedTo = 1;
    while (frameSize == C.INDEX_UNSET) {
      frameSize = findFrame(peeked, searchedTo);
      if (frameSize != C.INDEX_UNSET || peeked < SCAN_INCREMENT || peeked == MAX_FRAME_SIZE) {
        break;
      }
      searchedTo = peeked - 1;
      peeked = peekToScanBuffer(input, min(peeked + SCAN_INCREMENT, MAX_FRAME_SIZE));
    }
    if (frameSize == C.INDEX_UNSET) {
      if (peeked == MAX_FRAME_SIZE) {
        throw ParserException.createForMalformedContainer(
            "Could not find the end of a TAK frame", /* cause= */ null);
      }
      frameSize = peeked;
    }
    if (appendSampleData(input, castNonNull(trackOutput), frameSize) < frameSize) {
      return RESULT_END_OF_INPUT;
    }
    trackOutput.sampleMetadata(
        /* timeUs= */ sampleTimeUs(castNonNull(frameHeader).frameNumber),
        C.BUFFER_FLAG_KEY_FRAME,
        frameSize,
        /* offset= */ 0,
        /* cryptoData= */ null);
    return RESULT_CONTINUE;
  }

  // Internal methods.

  /** Reads the metadata blocks, and outputs the track format and a seek map. */
  private void startReading(ExtractorInput input) throws IOException {
    @Nullable Metadata id3Metadata = skipId3Data(input, id3Peeker, /* skip= */ true);
    input.skipFully(METADATA_HEADER_SIZE); // The file signature.
    readMetadataBlocks(input);

    TakStreamInfo streamInfo =
        checkStateNotNull(this.streamInfo, "The TAK file holds no stream information");
    firstFramePosition = input.getPosition();
    if (dataEndPosition == C.INDEX_UNSET) {
      dataEndPosition = input.getLength();
    }
    outputFormat(streamInfo, id3Metadata);
    startedReading = true;
  }

  /** Reads the chain of metadata blocks, leaving the read position at the first frame. */
  private void readMetadataBlocks(ExtractorInput input) throws IOException {
    while (true) {
      input.readFully(scratch.getData(), /* offset= */ 0, METADATA_HEADER_SIZE);
      scratch.setPosition(0);
      int type = scratch.readUnsignedByte() & 0x7F;
      int size = scratch.readLittleEndianUnsignedInt24();
      if (type == METADATA_TYPE_END) {
        return;
      }

      int bodySize = size - METADATA_CHECKSUM_SIZE;
      if (bodySize <= 0) {
        throw ParserException.createForMalformedContainer(
            "Invalid TAK metadata block size: " + size, /* cause= */ null);
      }
      if (type == METADATA_TYPE_STREAM_INFO || type == METADATA_TYPE_LAST_FRAME) {
        byte[] body = new byte[bodySize];
        input.readFully(body, /* offset= */ 0, bodySize);
        input.skipFully(METADATA_CHECKSUM_SIZE);
        if (type == METADATA_TYPE_STREAM_INFO) {
          streamInfo = TakStreamInfo.parse(new TakBitReader(body, /* offset= */ 0, bodySize));
          streamInfoBytes = body;
        } else if (bodySize == LAST_FRAME_BODY_SIZE) {
          TakBitReader reader = new TakBitReader(body, /* offset= */ 0, bodySize);
          dataEndPosition =
              reader.readBitsLong(LAST_FRAME_POSITION_BITS) + reader.readBits(LAST_FRAME_SIZE_BITS);
        }
      } else {
        input.skipFully(size);
      }
    }
  }

  /** Outputs the track format and a seek map that assumes a constant bitrate. */
  private void outputFormat(TakStreamInfo streamInfo, @Nullable Metadata id3Metadata) {
    long durationUs =
        streamInfo.sampleCount > 0
            ? Util.scaleLargeTimestamp(
                streamInfo.sampleCount, C.MICROS_PER_SECOND, streamInfo.sampleRate)
            : C.TIME_UNSET;
    long dataLength =
        dataEndPosition == C.INDEX_UNSET ? C.LENGTH_UNSET : dataEndPosition - firstFramePosition;
    int averageBitrate = getAverageBitrate(dataLength, durationUs);

    if (averageBitrate != Format.NO_VALUE) {
      long frameCount = Util.ceilDivide(streamInfo.sampleCount, streamInfo.frameSamples);
      checkStateNotNull(extractorOutput)
          .seekMap(
              new ConstantBitrateSeekMap(
                  dataEndPosition,
                  firstFramePosition,
                  averageBitrate,
                  /* frameSize= */ (int) (dataLength / frameCount)));
    } else {
      checkStateNotNull(extractorOutput).seekMap(new SeekMap.Unseekable(durationUs));
    }
    castNonNull(trackOutput)
        .format(
            new Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_TAK)
                .setChannelCount(streamInfo.channelCount)
                .setSampleRate(streamInfo.sampleRate)
                .setPcmEncoding(Util.getPcmEncoding(streamInfo.bitDepth))
                .setAverageBitrate(averageBitrate)
                .setInitializationData(ImmutableList.of(castNonNull(streamInfoBytes)))
                .setMetadata(mergedMetadata(id3Metadata))
                .build());
  }

  /** Peeks up to {@code length} bytes into the scan buffer, returning how many were peeked. */
  private int peekToScanBuffer(ExtractorInput input, int length) throws IOException {
    input.resetPeekPosition();
    int limit = length;
    if (dataEndPosition != C.INDEX_UNSET) {
      limit = (int) min(limit, dataEndPosition - input.getPosition());
    }
    int peeked = 0;
    while (peeked < limit) {
      int result = input.peek(scanBuffer, peeked, limit - peeked);
      if (result == C.RESULT_END_OF_INPUT) {
        break;
      }
      peeked += result;
    }
    return peeked;
  }

  /**
   * Returns the offset of the first frame at or after {@code startOffset} in the scan buffer, or
   * {@link C#INDEX_UNSET} if the buffer holds no frame.
   */
  private int findFrame(int limit, int startOffset) {
    for (int offset = startOffset; offset < limit - 1; offset++) {
      if (scanBuffer[offset] == TakFrameHeader.SYNC_BYTE_0
          && scanBuffer[offset + 1] == TakFrameHeader.SYNC_BYTE_1
          && TakFrameHeader.parse(scanBuffer, offset, limit) != null) {
        return offset;
      }
    }
    return C.INDEX_UNSET;
  }

  /** Returns the timestamp of the frame at {@code frameNumber}. */
  private long sampleTimeUs(int frameNumber) {
    TakStreamInfo streamInfo = castNonNull(this.streamInfo);
    return Util.scaleLargeTimestamp(
        (long) frameNumber * streamInfo.frameSamples, C.MICROS_PER_SECOND, streamInfo.sampleRate);
  }

  /**
   * Returns the metadata of the ID3 tag at the start of the file and of the APE tag at the end of
   * it, or null if the file holds neither.
   */
  @Nullable
  private Metadata mergedMetadata(@Nullable Metadata id3Metadata) {
    if (id3Metadata == null) {
      return apeMetadata;
    }
    return apeMetadata == null ? id3Metadata : id3Metadata.copyWithAppendedEntriesFrom(apeMetadata);
  }
}
