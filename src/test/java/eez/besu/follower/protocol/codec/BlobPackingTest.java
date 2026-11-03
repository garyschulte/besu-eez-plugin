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

import static eez.besu.follower.protocol.codec.BlobPacking.BLOB_SIZE;
import static eez.besu.follower.protocol.codec.BlobPacking.STREAM_BYTES_PER_BLOB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.MutableBytes;
import org.junit.jupiter.api.Test;

/**
 * Field-element packing (BLOB_FORMAT_SPEC §4). The serialized vectors are the ones in §4 and in
 * eez-core-protocol test/blob/BlobCodec.t.sol.
 */
class BlobPackingTest {

  private static final BigInteger BLS_MODULUS =
      new BigInteger("73eda753299d7d483339d80809a1d80553bda402fffe5bfeffffffff00000001", 16);

  private static final Bytes STREAM =
      Bytes.fromHexString("0x800102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1eaabb");
  private static final Bytes ELEMENT_0 =
      Bytes.fromHexString("0x001e1d1c1b1a191817161514131211100f0e0d0c0b0a09080706050403020180");
  private static final Bytes ELEMENT_1 =
      Bytes.fromHexString("0x000000000000000000000000000000000000000000000000000000000000bbaa");

  private static Bytes element(final Bytes blob, final int index) {
    return blob.slice(index * 32, 32);
  }

  @Test
  void packMatchesSpecVector() {
    final List<Bytes> blobs = BlobPacking.pack(STREAM);
    assertThat(blobs).hasSize(1);
    assertThat(blobs.getFirst().size()).isEqualTo(BLOB_SIZE);
    assertThat(element(blobs.getFirst(), 0)).isEqualTo(ELEMENT_0);
    assertThat(element(blobs.getFirst(), 1)).isEqualTo(ELEMENT_1);
    assertThat(blobs.getFirst().slice(64).isZero()).isTrue();
  }

  @Test
  void unpackMatchesSpecVector() {
    final MutableBytes blob = MutableBytes.create(BLOB_SIZE);
    blob.set(0, ELEMENT_0);
    blob.set(32, ELEMENT_1);
    final Bytes stream = BlobPacking.unpack(List.of(blob));
    assertThat(stream.size()).isEqualTo(STREAM_BYTES_PER_BLOB);
    assertThat(stream.slice(0, STREAM.size())).isEqualTo(STREAM);
    assertThat(stream.slice(STREAM.size()).isZero()).isTrue();
  }

  @Test
  void maximumChunkIsBelowBlsModulus() {
    final Bytes element =
        element(BlobPacking.pack(Bytes.fromHexString("0x" + "ff".repeat(31))).getFirst(), 0);
    assertThat(element).isEqualTo(Bytes.fromHexString("0x00" + "ff".repeat(31)));
    assertThat(element.toUnsignedBigInteger()).isLessThan(BLS_MODULUS);
  }

  @Test
  void streamSpansBlobBoundary() {
    final byte[] raw = new byte[STREAM_BYTES_PER_BLOB + 100];
    for (int i = 0; i < raw.length; i++) {
      raw[i] = (byte) (i * 31 + 7);
    }
    final Bytes stream = Bytes.wrap(raw);
    final List<Bytes> blobs = BlobPacking.pack(stream);
    assertThat(blobs).hasSize(2);
    final Bytes unpacked = BlobPacking.unpack(blobs);
    assertThat(unpacked.size()).isEqualTo(2 * STREAM_BYTES_PER_BLOB);
    assertThat(unpacked.slice(0, stream.size())).isEqualTo(stream);
    assertThat(unpacked.slice(stream.size()).isZero()).isTrue();
  }

  @Test
  void exactlyOneBlobOfCapacityNeedsOneBlob() {
    assertThat(BlobPacking.pack(Bytes.wrap(new byte[STREAM_BYTES_PER_BLOB]))).hasSize(1);
    assertThat(BlobPacking.pack(Bytes.wrap(new byte[STREAM_BYTES_PER_BLOB + 1]))).hasSize(2);
  }

  @Test
  void rejectsValidScalarOutsidePackingFormat() {
    final MutableBytes blob = MutableBytes.create(BLOB_SIZE);
    blob.set(5 * 32, (byte) 0x01);
    assertThat(element(blob, 5).toUnsignedBigInteger()).isLessThan(BLS_MODULUS);
    assertThatThrownBy(() -> BlobPacking.unpack(List.of(Bytes.wrap(new byte[BLOB_SIZE]), blob)))
        .isInstanceOf(PayloadDecodingException.class)
        .hasMessageContaining("blob 1 field element 5 has nonzero first byte 0x01");
  }

  @Test
  void rejectsInvalidScalar() {
    final MutableBytes blob = MutableBytes.create(BLOB_SIZE);
    blob.set(0, (byte) 0x74);
    assertThat(element(blob, 0).toUnsignedBigInteger()).isGreaterThanOrEqualTo(BLS_MODULUS);
    assertThatThrownBy(() -> BlobPacking.unpack(List.of(blob)))
        .isInstanceOf(PayloadDecodingException.class)
        .hasMessageContaining("blob 0 field element 0");
  }

  @Test
  void rejectsWrongBlobSize() {
    assertThatThrownBy(() -> BlobPacking.unpack(List.of(Bytes.wrap(new byte[BLOB_SIZE - 1]))))
        .isInstanceOf(PayloadDecodingException.class)
        .hasMessageContaining("blob 0 has 131071 bytes, expected 131072");
  }

  @Test
  void packRejectsEmptyStream() {
    assertThatThrownBy(() -> BlobPacking.pack(Bytes.EMPTY))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
