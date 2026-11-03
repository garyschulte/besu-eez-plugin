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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Runs every vector in eez-core-protocol docs/blobs/U256_COMPRESSED_CODEC_VECTORS.json. */
class U256CompressedCodecTest {

  private static final JsonNode VECTORS = loadVectors();

  private static JsonNode loadVectors() {
    try (InputStream in =
        U256CompressedCodecTest.class.getResourceAsStream(
            "/protocol/codec/U256_COMPRESSED_CODEC_VECTORS.json")) {
      return new ObjectMapper().readTree(in);
    } catch (final IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static UInt256 value(final JsonNode node) {
    return UInt256.valueOf(new BigInteger(node.asText()));
  }

  private static Bytes hex(final JsonNode node) {
    return Bytes.fromHexString(node.asText());
  }

  static Stream<Arguments> canonicalVectors() {
    final List<Arguments> args = new ArrayList<>();
    for (final String section : List.of("edge_cases", "per_format_code")) {
      VECTORS
          .get(section)
          .forEach(
              v ->
                  args.add(
                      Arguments.of(
                          section + ": " + v.get("note").asText(),
                          value(v.get("value")),
                          hex(v.get("hex")))));
    }
    return args.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("canonicalVectors")
  void canonicalVectorRoundTrips(final String name, final UInt256 value, final Bytes encoded) {
    assertThat(U256CompressedCodec.encode(value)).isEqualTo(encoded);
    final U256CompressedCodec.Decoded decoded = U256CompressedCodec.decode(encoded, 0);
    assertThat(decoded.value()).isEqualTo(value);
    assertThat(decoded.nextOffset()).isEqualTo(encoded.size());
  }

  @Test
  void perFormatCodeCoversEveryAssignedCode() {
    final List<Integer> codes = new ArrayList<>();
    VECTORS.get("per_format_code").forEach(v -> codes.add(v.get("code").asInt()));
    assertThat(codes).hasSize(253);
    assertThat(codes).doesNotHaveDuplicates().allMatch(c -> c >= 0 && c <= 252);
  }

  @Test
  void decodeOnlyVectorsDecodeAndReencodeCanonically() {
    assertThat(VECTORS.get("decode_only")).isNotEmpty();
    VECTORS
        .get("decode_only")
        .forEach(
            v -> {
              final Bytes encoded = hex(v.get("hex"));
              final U256CompressedCodec.Decoded decoded = U256CompressedCodec.decode(encoded, 0);
              assertThat(decoded.value()).isEqualTo(value(v.get("value")));
              assertThat(decoded.nextOffset()).isEqualTo(encoded.size());
              assertThat(U256CompressedCodec.encode(decoded.value()))
                  .isEqualTo(hex(v.get("canonical_hex")));
            });
  }

  @Test
  void invalidVectorsAreRejected() {
    assertThat(VECTORS.get("invalid")).isNotEmpty();
    VECTORS
        .get("invalid")
        .forEach(
            v -> {
              final String expected = v.get("error").asText();
              assertThatThrownBy(() -> U256CompressedCodec.decode(hex(v.get("hex")), 0))
                  .as(v.get("note").asText())
                  .isInstanceOf(PayloadDecodingException.class)
                  .hasMessageContaining(expected);
            });
  }

  @Test
  void streamsDecodeSelfDelimited() {
    VECTORS
        .get("streams")
        .forEach(
            v -> {
              final Bytes stream = hex(v.get("hex"));
              final List<UInt256> values = new ArrayList<>();
              int offset = 0;
              while (offset < stream.size()) {
                final U256CompressedCodec.Decoded decoded =
                    U256CompressedCodec.decode(stream, offset);
                values.add(decoded.value());
                offset = decoded.nextOffset();
              }
              final List<UInt256> expected = new ArrayList<>();
              v.get("values").forEach(n -> expected.add(value(n)));
              assertThat(values).isEqualTo(expected);
              assertThat(
                      Bytes.concatenate(
                          expected.stream().map(U256CompressedCodec::encode).toArray(Bytes[]::new)))
                  .isEqualTo(stream);
            });
  }

  @Test
  void decodeRejectsMissingFormatByte() {
    assertThatThrownBy(() -> U256CompressedCodec.decode(Bytes.EMPTY, 0))
        .isInstanceOf(PayloadDecodingException.class)
        .hasMessageContaining("missing format byte");
  }
}
