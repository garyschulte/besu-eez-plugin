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
package eez.besu.follower.protocol.eez.abi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.hyperledger.besu.datatypes.Address;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;

class AbiTest {

  private static final Address TARGET = Address.fromHexString("0xdeadbeef");
  private static final Address SOURCE = Address.fromHexString("0xc0ffee");

  // Legacy EEZ wire vectors from eez-specs docs/eez-protocol-spec/05-wire-formats.md §5.8 and
  // companion-docs/wire-vectors.s.sol. Their struct shapes predate eez-core 84a5b52, so they serve
  // here as ABI known answers rather than protocol vectors.
  private static final String LEGACY_ENTRY_TYPE =
      "((uint256,bytes32,bytes32,int256)[],bytes32,uint256,"
          + "(address,uint256,bytes,address,uint256,uint256)[],"
          + "(bytes32,uint256,bytes)[],uint256,bytes,bytes32)";
  private static final Bytes32 LEGACY_CALL_HASH =
      Bytes32.fromHexString("0x6254f201d3301530dd7b10596ae0cfbc81512ae249819956824df2260cb8c2a2");
  private static final Bytes32 LEGACY_ROLLING_HASH =
      Bytes32.fromHexString("0x696336455de0a48486231a12058b65434a928dc21e0d6ce8b8e80179ac480e7d");
  private static final Bytes LEGACY_ENTRY_ENCODING =
      Bytes.fromHexString(
          "0x0000000000000000000000000000000000000000000000000000000000000020"
              + "0000000000000000000000000000000000000000000000000000000000000100"
              + "6254f201d3301530dd7b10596ae0cfbc81512ae249819956824df2260cb8c2a2"
              + "0000000000000000000000000000000000000000000000000000000000000001"
              + "00000000000000000000000000000000000000000000000000000000000001a0"
              + "00000000000000000000000000000000000000000000000000000000000002e0"
              + "0000000000000000000000000000000000000000000000000000000000000001"
              + "0000000000000000000000000000000000000000000000000000000000000300"
              + "696336455de0a48486231a12058b65434a928dc21e0d6ce8b8e80179ac480e7d"
              + "0000000000000000000000000000000000000000000000000000000000000001"
              + "0000000000000000000000000000000000000000000000000000000000000001"
              + "00000000000000000000000000000000000000000000000000000000000000aa"
              + "00000000000000000000000000000000000000000000000000000000000000bb"
              + "0000000000000000000000000000000000000000000000000000000000000000"
              + "0000000000000000000000000000000000000000000000000000000000000001"
              + "0000000000000000000000000000000000000000000000000000000000000020"
              + "00000000000000000000000000000000000000000000000000000000deadbeef"
              + "0000000000000000000000000000000000000000000000000000000000000000"
              + "00000000000000000000000000000000000000000000000000000000000000c0"
              + "0000000000000000000000000000000000000000000000000000000000c0ffee"
              + "0000000000000000000000000000000000000000000000000000000000000000"
              + "0000000000000000000000000000000000000000000000000000000000000000"
              + "0000000000000000000000000000000000000000000000000000000000000004"
              + "deadbeef00000000000000000000000000000000000000000000000000000000"
              + "0000000000000000000000000000000000000000000000000000000000000000"
              + "0000000000000000000000000000000000000000000000000000000000000000");
  private static final Bytes32 LEGACY_ENTRY_HASH =
      Bytes32.fromHexString("0xf55a0b2f661170eb3d02881188f6fc27ce192d6653a2c45c3d6b9bc8e368ce39");

  @Test
  void selectorsMatchKnownAnswers() {
    assertThat(Abi.selector("transfer(address,uint256)"))
        .isEqualTo(Bytes.fromHexString("a9059cbb"));
    // eez-core pins (rollupX abi.rs selector locks; executeIncoming/postAndVerifyBatch re-derived
    // by Foundry at rev 84a5b52)
    assertThat(Abi.selector("executeOnBehalf(address,uint64,bytes)"))
        .isEqualTo(Bytes.fromHexString("8205f3e1"));
    assertThat(Abi.selector("createCrossChainProxy(address,uint64)"))
        .isEqualTo(Bytes.fromHexString("a7587c62"));
    assertThat(Abi.selector("computeCrossChainProxyAddress(address,uint64)"))
        .isEqualTo(Bytes.fromHexString("eb20c0aa"));
    assertThat(Abi.selector("executeL2Txs(uint64)")).isEqualTo(Bytes.fromHexString("dc6d11fa"));
    assertThat(Abi.selector("staticCrossChainCall(address,bytes)"))
        .isEqualTo(Bytes.fromHexString("31344ade"));
  }

  @Test
  void eventTopicMatchesKnownAnswer() {
    final AbiEvent transfer = AbiEvent.parse("Transfer(address indexed,address indexed,uint256)");
    assertThat(transfer.signature()).isEqualTo("Transfer(address,address,uint256)");
    assertThat(transfer.topic0())
        .isEqualTo(
            Bytes32.fromHexString(
                "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"));
  }

  @Test
  void legacyCallHashVector() {
    final AbiType.Tuple types =
        AbiType.parseTuple("(uint256,address,uint256,bytes,address,uint256)");
    final Bytes encoded =
        Abi.encode(
            types,
            List.of(
                1L,
                TARGET,
                new BigInteger("1000000000000000000"),
                Bytes.fromHexString("deadbeef"),
                SOURCE,
                0L));
    assertThat(Abi.keccak256(encoded)).isEqualTo(LEGACY_CALL_HASH);
  }

  @Test
  void legacyExecutionEntryVector() {
    final AbiType type = AbiType.parse(LEGACY_ENTRY_TYPE);
    final List<Object> entry =
        List.of(
            List.of(List.of(1L, word(0xaa), word(0xbb), BigInteger.ZERO)),
            LEGACY_CALL_HASH,
            1L,
            List.of(List.of(TARGET, 0L, Bytes.fromHexString("deadbeef"), SOURCE, 0L, 0L)),
            List.of(),
            1L,
            Bytes.EMPTY,
            LEGACY_ROLLING_HASH);
    final Bytes encoded = Abi.encodeSingle(type, entry);
    assertThat(encoded).isEqualTo(LEGACY_ENTRY_ENCODING);
    assertThat(Abi.keccak256(encoded)).isEqualTo(LEGACY_ENTRY_HASH);
    assertThat(Abi.encodeSingle(type, Abi.decodeSingle(type, encoded))).isEqualTo(encoded);
  }

  @Test
  void legacyPublicInputVectors() {
    final AbiType hashes = AbiType.parse("bytes32[]");
    final Bytes empty = Abi.encodeSingle(hashes, List.of());
    assertThat(empty).isEqualTo(Bytes.concatenate(word(32), word(0)));

    // Vector 5/6: sharedPublicInput with one entry hash, no lookups, no blobs, empty callData
    final Bytes32 shared =
        Abi.keccak256(
            Bytes.concatenate(
                Abi.encodeSingle(hashes, List.of(LEGACY_ENTRY_HASH)),
                empty,
                empty,
                Abi.keccak256(Bytes.EMPTY),
                Bytes32.ZERO));
    assertThat(shared)
        .isEqualTo(
            Bytes32.fromHexString(
                "0x9c4e7a3a4b9037140a02de516a8b3200968754093f4e585e1fd742b26adcb790"));

    final AbiType.Tuple fold = AbiType.parseTuple("(bytes32,uint256,bytes32,bytes32,uint256)");
    final Bytes32 acc0 =
        Abi.keccak256(Abi.encode(fold, List.of(Bytes32.ZERO, 1L, word(0x100), Bytes32.ZERO, 0L)));
    assertThat(acc0)
        .isEqualTo(
            Bytes32.fromHexString(
                "0x3b75d0aa6402ebada19a4aee44555af338742741e95ea3973d14aad60255d700"));
    assertThat(Abi.keccak256(Bytes.concatenate(shared, acc0)))
        .isEqualTo(
            Bytes32.fromHexString(
                "0xdcd57c2f57d42af6165678f3adbb7e07e0b4ba60765bd2e4c4a76921e8daa891"));

    Bytes32 acc1 =
        Abi.keccak256(Abi.encode(fold, List.of(Bytes32.ZERO, 1L, word(0x101), Bytes32.ZERO, 0L)));
    acc1 = Abi.keccak256(Abi.encode(fold, List.of(acc1, 2L, word(0x201), Bytes32.ZERO, 0L)));
    assertThat(acc1)
        .isEqualTo(
            Bytes32.fromHexString(
                "0x1d1754cd9a1ce70d42038aeb1110bad91b9500d6a586e07f93ffb690a18222e6"));
    assertThat(Abi.keccak256(Bytes.concatenate(shared, acc1)))
        .isEqualTo(
            Bytes32.fromHexString(
                "0x8821cb36904d46326985e8800791e6da3536634a68905c8823199772a2ba6643"));
  }

  @Test
  void legacyLookupVector() {
    final AbiType lookup =
        AbiType.parse(
            "(bytes32,uint256,bytes,bool,uint64,uint64,"
                + "(address,uint256,bytes,address,uint256,uint256)[],bytes32)");
    final Bytes32 lookupHash =
        Abi.keccak256(
            Abi.encodeSingle(
                lookup,
                List.of(
                    word(0x1234),
                    1L,
                    Bytes.fromHexString("abcd"),
                    true,
                    0L,
                    0L,
                    List.of(),
                    Bytes32.ZERO)));
    assertThat(lookupHash)
        .isEqualTo(
            Bytes32.fromHexString(
                "0x30690221b19c8bbd95cd1379a35163a58e1c340e5689618d62583084ae4e0d43"));

    final AbiType hashes = AbiType.parse("bytes32[]");
    final Bytes empty = Abi.encodeSingle(hashes, List.of());
    final Bytes32 xpsi = Abi.keccak256(Bytes.wrap("xpsi".getBytes(StandardCharsets.US_ASCII)));
    final Bytes32 shared =
        Abi.keccak256(
            Bytes.concatenate(
                Abi.encodeSingle(hashes, List.of(LEGACY_ENTRY_HASH)),
                Abi.encodeSingle(hashes, List.of(lookupHash)),
                empty,
                Abi.keccak256(Bytes.fromHexString("cafebabe")),
                xpsi));
    assertThat(shared)
        .isEqualTo(
            Bytes32.fromHexString(
                "0x0c75410a2dca7acce877a745705bde1721f360436e4732068d0e013c4bbd96d8"));
  }

  @Test
  void roundTripsNestedDynamicValues() {
    final AbiType.Tuple types =
        AbiType.parseTuple(
            "(int192,(address,bytes,uint16[])[],bytes4,bool,(uint8,bytes32),bytes[])");
    final List<Object> values =
        List.of(
            BigInteger.TWO.pow(191).negate(),
            List.of(
                List.of(SOURCE, Bytes.EMPTY, List.of(BigInteger.ONE, BigInteger.valueOf(65535))),
                List.of(TARGET, Bytes.fromHexString("0x" + "ab".repeat(33)), List.of())),
            Bytes.fromHexString("0x01020304"),
            true,
            List.of(BigInteger.valueOf(255), word(7)),
            List.of(Bytes.EMPTY, Bytes.fromHexString("ff")));
    final Bytes encoded = Abi.encode(types, values);
    final List<Object> decoded = Abi.decode(types, encoded);
    assertThat(Abi.encode(types, decoded)).isEqualTo(encoded);
    assertThat(decoded.get(0)).isEqualTo(BigInteger.TWO.pow(191).negate());
    assertThat(decoded.get(2)).isEqualTo(Bytes.fromHexString("0x01020304"));
    assertThat(decoded.get(4)).isEqualTo(List.of(BigInteger.valueOf(255), word(7)));
  }

  @Test
  void encodesNegativeIntegersAsTwosComplement() {
    assertThat(Abi.encodeSingle(new AbiType.Int(192), BigInteger.valueOf(-1)))
        .isEqualTo(Bytes32.fromHexString("0x" + "ff".repeat(32)));
    assertThatThrownBy(() -> Abi.encodeSingle(new AbiType.Int(192), BigInteger.TWO.pow(191)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Abi.encodeSingle(new AbiType.Uint(64), BigInteger.TWO.pow(64)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void decodesNonCanonicalOffsetsLikeSolidity() {
    // abi.encode(bytes) with a 32-byte gap between the head and the content
    final Bytes data =
        Bytes.concatenate(word(64), word(0x99), word(2), Bytes32.rightPad(Bytes.of(1, 2)));
    assertThat(Abi.decodeSingle(new AbiType.DynamicBytes(), data)).isEqualTo(Bytes.of(1, 2));
  }

  @Test
  void rejectsDirtyOrOutOfRangeInput() {
    assertThatThrownBy(() -> Abi.decodeSingle(new AbiType.AddressType(), word(1).not()))
        .isInstanceOf(AbiDecodingException.class);
    assertThatThrownBy(() -> Abi.decodeSingle(new AbiType.Bool(), word(2)))
        .isInstanceOf(AbiDecodingException.class);
    assertThatThrownBy(() -> Abi.decodeSingle(new AbiType.Uint(64), word(1).shiftLeft(64)))
        .isInstanceOf(AbiDecodingException.class);
    assertThatThrownBy(() -> Abi.decodeSingle(new AbiType.FixedBytes(4), word(1)))
        .isInstanceOf(AbiDecodingException.class);
    assertThatThrownBy(() -> Abi.decodeSingle(new AbiType.Int(8), word(128)))
        .isInstanceOf(AbiDecodingException.class);
    // offset beyond the input
    assertThatThrownBy(() -> Abi.decodeSingle(new AbiType.DynamicBytes(), word(4096)))
        .isInstanceOf(AbiDecodingException.class);
    // length beyond the input
    assertThatThrownBy(
            () ->
                Abi.decodeSingle(new AbiType.DynamicBytes(), Bytes.concatenate(word(32), word(33))))
        .isInstanceOf(AbiDecodingException.class);
    // an array length that cannot fit, rejected before allocation
    assertThatThrownBy(
            () ->
                Abi.decodeSingle(
                    AbiType.parse("uint256[]"),
                    Bytes.concatenate(word(32), Bytes32.fromHexString("0x" + "ff".repeat(32)))))
        .isInstanceOf(AbiDecodingException.class);
    assertThatThrownBy(
            () ->
                Abi.decodeSingle(
                    AbiType.parse("uint256[]"), Bytes.concatenate(word(32), word(2), word(1))))
        .isInstanceOf(AbiDecodingException.class);
    // truncated head
    assertThatThrownBy(() -> Abi.decode(AbiType.parseTuple("(uint256,uint256)"), word(1)))
        .isInstanceOf(AbiDecodingException.class);
  }

  @Test
  void parsesAndPrintsCanonicalTypes() {
    final String type =
        "((uint64,int192,bytes32,bytes32)[],bytes32,(uint16,bool)[][],address,bytes)";
    assertThat(AbiType.parse(type).canonical()).isEqualTo(type);
    assertThat(AbiType.parse("uint").canonical()).isEqualTo("uint256");
    assertThat(AbiType.parse("()").canonical()).isEqualTo("()");
    assertThatThrownBy(() -> AbiType.parse("uint7")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AbiType.parse("string")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AbiType.parse("(uint8")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AbiType.parse("uint8[2]"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void functionEncodesAndDecodesCalls() {
    final AbiFunction transfer = AbiFunction.parse("transfer(address,uint256)");
    final Bytes call = transfer.encodeCall(List.of(TARGET, 5L));
    assertThat(call)
        .isEqualTo(
            Bytes.concatenate(
                Bytes.fromHexString("a9059cbb"), Bytes32.leftPad(TARGET.getBytes()), word(5)));
    assertThat(transfer.decodeCall(call)).containsExactly(TARGET, BigInteger.valueOf(5));
    assertThatThrownBy(() -> transfer.decodeCall(Bytes.fromHexString("deadbeef")))
        .isInstanceOf(AbiDecodingException.class);
  }

  private static Bytes32 word(final long value) {
    return Bytes32.leftPad(Bytes.ofUnsignedLong(value));
  }
}
