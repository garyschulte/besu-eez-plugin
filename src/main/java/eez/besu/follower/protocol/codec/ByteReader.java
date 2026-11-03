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

import org.apache.tuweni.bytes.Bytes;

/** Bounds-checked forward cursor over a byte string. Every read failure is a truncation. */
final class ByteReader {

  private final Bytes bytes;
  private final String context;
  private int position;

  ByteReader(final Bytes bytes, final String context) {
    this.bytes = bytes;
    this.context = context;
  }

  int position() {
    return position;
  }

  int remaining() {
    return bytes.size() - position;
  }

  boolean hasRemaining() {
    return position < bytes.size();
  }

  int peekUnsignedByte() {
    require(1, "byte");
    return bytes.get(position) & 0xff;
  }

  int readUnsignedByte() {
    require(1, "byte");
    return bytes.get(position++) & 0xff;
  }

  /** Returns a copy, so decoded values never pin the (possibly large) source buffer. */
  Bytes readBytes(final long length, final String field) {
    require(length, field);
    final Bytes out = bytes.slice(position, (int) length).copy();
    position += (int) length;
    return out;
  }

  /** Reads a fixed-width little-endian unsigned 64-bit value (returned as a raw long). */
  long readU64LittleEndian(final String field) {
    require(8, field);
    long value = 0;
    for (int i = 0; i < 8; i++) {
      value |= (long) (bytes.get(position + i) & 0xff) << (8 * i);
    }
    position += 8;
    return value;
  }

  private void require(final long length, final String field) {
    if (length > remaining()) {
      throw new PayloadDecodingException(
          String.format(
              "%s: truncated %s at offset %d (need %d bytes, %d remain)",
              context, field, position, length, remaining()));
    }
  }
}
