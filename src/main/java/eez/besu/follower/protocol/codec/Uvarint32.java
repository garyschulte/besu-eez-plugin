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

import java.io.ByteArrayOutputStream;

/**
 * Protobuf-style unsigned Base128 varints limited to {@code [0, 2^32 - 1]}.
 *
 * <p>Two read modes exist because the two layers disagree on canonicality: the Rollup0 payload
 * (D.4.2) requires the shortest form, while the EEZ message stream (BLOB_FORMAT_SPEC §5 condition
 * 8) accepts non-minimal encodings.
 */
final class Uvarint32 {

  static final long MAX_VALUE = 0xffff_ffffL;
  private static final int MAX_BYTES = 5;

  private Uvarint32() {}

  /** Reads a shortest-form uvarint32 (Rollup0 D.4.2). */
  static long readCanonical(final ByteReader reader, final String field) {
    return read(reader, field, true);
  }

  /** Reads a uvarint32 that may be non-minimal (EEZ §1.1, §5 condition 8). */
  static long readLenient(final ByteReader reader, final String field) {
    return read(reader, field, false);
  }

  private static long read(final ByteReader reader, final String field, final boolean canonical) {
    final int start = reader.position();
    long value = 0;
    for (int i = 0; i < MAX_BYTES; i++) {
      if (!reader.hasRemaining()) {
        throw new PayloadDecodingException(
            String.format("truncated varint %s at offset %d", field, start));
      }
      final int b = reader.readUnsignedByte();
      if (i == MAX_BYTES - 1 && b > 0x0f) {
        throw new PayloadDecodingException(
            String.format(
                "varint %s at offset %d exceeds 2^32-1 (fifth byte 0x%02x > 0x0f)",
                field, start, b));
      }
      value |= (long) (b & 0x7f) << (7 * i);
      if ((b & 0x80) == 0) {
        if (canonical && i > 0 && b == 0) {
          throw new PayloadDecodingException(
              String.format("non-shortest varint %s at offset %d", field, start));
        }
        return value;
      }
    }
    // unreachable: a fifth byte <= 0x0f has no continuation bit
    throw new IllegalStateException("varint loop exited without terminating");
  }

  static void write(final long value, final ByteArrayOutputStream out) {
    checkRange(value);
    long v = value;
    while (v >= 0x80) {
      out.write((int) ((v & 0x7f) | 0x80));
      v >>>= 7;
    }
    out.write((int) v);
  }

  static int encodedLength(final long value) {
    checkRange(value);
    int length = 1;
    long v = value;
    while (v >= 0x80) {
      v >>>= 7;
      length++;
    }
    return length;
  }

  private static void checkRange(final long value) {
    if (value < 0 || value > MAX_VALUE) {
      throw new IllegalArgumentException("uvarint32 value out of range: " + value);
    }
  }
}
