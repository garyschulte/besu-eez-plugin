/*
 * Copyright contributors to besu-eez-plugin.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package eez.besu.follower.protocol.codec;

import java.util.ArrayList;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;

/**
 * EEZ blob field-element packing (BLOB_FORMAT_SPEC §4): each 32-byte big-endian field element
 * carries 31 stream bytes, least significant first, and its first serialized byte must be zero.
 */
public final class BlobPacking {

  public static final int FIELD_ELEMENTS_PER_BLOB = 4096;
  public static final int BYTES_PER_FIELD_ELEMENT = 32;
  public static final int STREAM_BYTES_PER_FIELD_ELEMENT = 31;
  public static final int BLOB_SIZE = FIELD_ELEMENTS_PER_BLOB * BYTES_PER_FIELD_ELEMENT;
  public static final int STREAM_BYTES_PER_BLOB =
      FIELD_ELEMENTS_PER_BLOB * STREAM_BYTES_PER_FIELD_ELEMENT;

  private BlobPacking() {}

  /**
   * Recovers the blob portion of the logical stream, including its trailing zero padding.
   *
   * @param blobs the batch's blobs in order, each exactly {@value #BLOB_SIZE} bytes
   * @return the concatenated 31-byte chunks of every element of every blob
   * @throws PayloadDecodingException if a blob has the wrong size or an element's first byte is set
   */
  public static Bytes unpack(final List<Bytes> blobs) {
    final byte[] stream = new byte[Math.multiplyExact(blobs.size(), STREAM_BYTES_PER_BLOB)];
    int out = 0;
    for (int b = 0; b < blobs.size(); b++) {
      final Bytes blob = blobs.get(b);
      if (blob.size() != BLOB_SIZE) {
        throw new PayloadDecodingException(
            String.format("blob %d has %d bytes, expected %d", b, blob.size(), BLOB_SIZE));
      }
      final byte[] raw = blob.toArrayUnsafe();
      for (int e = 0; e < FIELD_ELEMENTS_PER_BLOB; e++) {
        final int base = e * BYTES_PER_FIELD_ELEMENT;
        if (raw[base] != 0) {
          throw new PayloadDecodingException(
              String.format(
                  "blob %d field element %d has nonzero first byte 0x%02x",
                  b, e, raw[base] & 0xff));
        }
        for (int k = 0; k < STREAM_BYTES_PER_FIELD_ELEMENT; k++) {
          stream[out++] = raw[base + BYTES_PER_FIELD_ELEMENT - 1 - k];
        }
      }
    }
    return Bytes.wrap(stream);
  }

  /**
   * Packs the blob portion of a logical stream into full-size blobs, zero-padding the last one.
   *
   * @param blobPortion the stream bytes from the version byte through {@code CloseBlobStream}
   * @return the minimal number of blobs holding those bytes
   */
  public static List<Bytes> pack(final Bytes blobPortion) {
    if (blobPortion.isEmpty()) {
      throw new IllegalArgumentException("blob portion must not be empty");
    }
    final int blobCount = (blobPortion.size() + STREAM_BYTES_PER_BLOB - 1) / STREAM_BYTES_PER_BLOB;
    final List<Bytes> blobs = new ArrayList<>(blobCount);
    int in = 0;
    for (int b = 0; b < blobCount; b++) {
      final byte[] blob = new byte[BLOB_SIZE];
      for (int e = 0; e < FIELD_ELEMENTS_PER_BLOB && in < blobPortion.size(); e++) {
        final int base = e * BYTES_PER_FIELD_ELEMENT;
        for (int k = 0; k < STREAM_BYTES_PER_FIELD_ELEMENT && in < blobPortion.size(); k++) {
          blob[base + BYTES_PER_FIELD_ELEMENT - 1 - k] = blobPortion.get(in++);
        }
      }
      blobs.add(Bytes.wrap(blob));
    }
    return blobs;
  }
}
