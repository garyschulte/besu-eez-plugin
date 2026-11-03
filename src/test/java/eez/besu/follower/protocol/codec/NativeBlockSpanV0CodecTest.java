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

import static eez.besu.follower.protocol.codec.NativeBlockSpanV0Codec.PRODUCTION_POSITIONS_PER_INTERVAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.hyperledger.besu.crypto.Hash;
import org.hyperledger.besu.datatypes.Address;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class NativeBlockSpanV0CodecTest {

  private static final Address A =
      Address.fromHexString("0x1111111111111111111111111111111111111111");
  private static final Address B =
      Address.fromHexString("0x2222222222222222222222222222222222222222");
  private static final Bytes TX = Bytes.fromHexString("0x02c0ffee");

  private static NativeBlockSpan decode(final String hex) {
    return NativeBlockSpanV0Codec.decode(Bytes.fromHexString(hex));
  }

  private static void assertRejected(final String hex, final String reason) {
    assertThatThrownBy(() -> decode(hex))
        .isInstanceOf(PayloadDecodingException.class)
        .hasMessageContaining(reason);
  }

  private static NativeBlock block(
      final Address beneficiary, final String extraData, final Bytes... transactions) {
    return new NativeBlock(beneficiary, Bytes.fromHexString(extraData), List.of(transactions));
  }

  /** Rollup0 D.4.4 initial V0 codec vectors, with their exact expected outcomes. */
  @Nested
  class SpecVectors {

    @Test
    void vector1SixEmptyBlocks() {
      final String hex = "0x00060000000000000600000000000000000000000000000000000000000600";
      assertThat(Bytes.fromHexString(hex).size()).isEqualTo(31);
      final NativeBlockSpan span = decode(hex);
      assertThat(span.blockCount()).isEqualTo(6);
      assertThat(span.blocks())
          .allSatisfy(
              b -> {
                assertThat(b.beneficiary()).isEqualTo(Address.ZERO);
                assertThat(b.extraData()).isEqualTo(Bytes.EMPTY);
                assertThat(b.transactions()).isEmpty();
              });
      assertThat(NativeBlockSpanV0Codec.encode(span)).isEqualTo(Bytes.fromHexString(hex));
      NativeBlockSpanV0Codec.decode(Bytes.fromHexString(hex), PRODUCTION_POSITIONS_PER_INTERVAL);
    }

    @Test
    void vector2OneTransactionAndMultipleMetadataRuns() {
      final String hex =
          "0x00060100000000000211111111111111111111111111111111111111110422222222222222222222222222222222222222220101aa0202bbcc03006f02f86c820539808405f5e100843b9aca0082520894000000000000000000000000000000000000dead8080c001a04849ec4d7eed2e9eb1da330cdb0a22cdb4c1e32d682556dcc2b491ea5d747dfda05e38e68ce47b2ae70e6b36de8f1e1413df4dbc73a30610675f0a8e8c1505e7a7";
      assertThat(Bytes.fromHexString(hex).size()).isEqualTo(171);
      final NativeBlockSpan span = decode(hex);
      assertThat(span.blockCount()).isEqualTo(6);
      assertThat(span.blocks().stream().map(b -> b.transactions().size()))
          .containsExactly(1, 0, 0, 0, 0, 0);
      assertThat(span.blocks().stream().map(NativeBlock::beneficiary))
          .containsExactly(A, A, B, B, B, B);
      assertThat(span.blocks().stream().map(NativeBlock::extraData))
          .containsExactly(
              Bytes.fromHexString("0xaa"),
              Bytes.fromHexString("0xbbcc"),
              Bytes.fromHexString("0xbbcc"),
              Bytes.EMPTY,
              Bytes.EMPTY,
              Bytes.EMPTY);
      final Bytes tx = span.blocks().getFirst().transactions().getFirst();
      assertThat(tx.size()).isEqualTo(111);
      assertThat(tx.get(0)).isEqualTo((byte) 0x02);
      assertThat(Hash.keccak256(tx))
          .isEqualTo(
              Bytes.fromHexString(
                  "0xfdf9c966ac23a592fda032328421cabc27414887b3496d1c6faec3b10e1f9b59"));
      assertThat(NativeBlockSpanV0Codec.encode(span)).isEqualTo(Bytes.fromHexString(hex));
    }

    @Test
    void vector3EmptyPayload() {
      assertRejected("0x", "payload version byte is absent");
    }

    @Test
    void vector4UnknownPayloadVersion() {
      assertRejected("0x01", "unknown payload version 0x01");
    }

    @Test
    void vector5NonShortestBlockCount() {
      assertRejected("0x008600", "non-shortest varint block_count");
    }

    @Test
    void vector6ImpossibleBlockCount() {
      assertRejected("0x0006", "block_count 6 exceeds the 0 remaining bytes");
    }

    @Test
    void vector7TrailingByte() {
      assertRejected(
          "0x0006000000000000060000000000000000000000000000000000000000060000", "trailing bytes");
    }
  }

  @Nested
  class Uvarints {

    @Test
    void zeroBlockCountRejected() {
      assertRejected("0x0000", "block_count is zero");
    }

    @Test
    void truncatedVarintRejected() {
      assertRejected("0x0080", "truncated varint block_count");
    }

    @Test
    void fifthByteAbove0x0fRejected() {
      assertRejected("0x00ffffffff10", "exceeds 2^32-1");
    }

    @Test
    void maximumFiveByteValueIsCanonical() {
      // 2^32-1 blocks cannot fit, so the early bound is what rejects it
      assertRejected("0x00ffffffff0f", "block_count 4294967295 exceeds");
    }

    @Test
    void nonShortestTransactionCountRejected() {
      assertRejected("0x00018000", "non-shortest varint pure_transaction_counts[0]");
    }

    @Test
    void nonShortestRunLengthRejected() {
      assertRejected(
          "0x00010081000000000000000000000000000000000000000000010000",
          "non-shortest varint beneficiary run length 0");
    }

    @Test
    void nonShortestTransactionLengthRejected() {
      final Bytes valid = NativeBlockSpanV0Codec.encode(span(block(A, "0x", TX)));
      // replace the one-byte length 0x04 with the two-byte 0x84 0x00
      final Bytes head = valid.slice(0, valid.size() - TX.size() - 1);
      assertThatThrownBy(
              () ->
                  NativeBlockSpanV0Codec.decode(
                      Bytes.concatenate(head, Bytes.fromHexString("0x8400"), TX)))
          .isInstanceOf(PayloadDecodingException.class)
          .hasMessageContaining("non-shortest varint pure_transaction_lengths[0]");
    }

    @Test
    void multiByteVarintsRoundTrip() {
      final Bytes bigTx = Bytes.wrap(new byte[300]);
      final List<NativeBlock> blocks = new ArrayList<>();
      blocks.add(block(A, "0x", bigTx));
      blocks.addAll(Collections.nCopies(199, block(A, "0x")));
      final Bytes encoded = NativeBlockSpanV0Codec.encode(new NativeBlockSpan(blocks));
      // block_count 200 = c8 01; one beneficiary run and one extraData run of 200; length 300 = ac
      // 02
      assertThat(encoded.slice(0, 3)).isEqualTo(Bytes.fromHexString("0x00c801"));
      assertThat(encoded.slice(encoded.size() - 302, 2)).isEqualTo(Bytes.fromHexString("0xac02"));
      assertThat(NativeBlockSpanV0Codec.decode(encoded).blocks()).isEqualTo(blocks);
    }
  }

  @Nested
  class Runs {

    @Test
    void zeroBeneficiaryRunRejected() {
      assertRejected(
          "0x0001000011111111111111111111111111111111111111110100",
          "beneficiary run 0 has zero length");
    }

    @Test
    void beneficiaryRunOvershootRejected() {
      assertRejected(
          "0x000100021111111111111111111111111111111111111111",
          "beneficiary run 0 of length 2 overshoots");
    }

    @Test
    void secondBeneficiaryRunOvershootRejected() {
      assertRejected(
          "0x0002000001111111111111111111111111111111111111111102222222222222222222222222222222222222222201",
          "beneficiary run 1 of length 2 overshoots block_count 2");
    }

    @Test
    void repeatedBeneficiaryRunRejected() {
      assertRejected(
          "0x0002000001111111111111111111111111111111111111111101111111111111111111111111111111111111111102",
          "beneficiary run 1 repeats");
    }

    @Test
    void zeroExtraDataRunRejected() {
      assertRejected(
          "0x00010001111111111111111111111111111111111111111100",
          "extraData run 0 has zero length");
    }

    @Test
    void extraDataRunOvershootRejected() {
      assertRejected(
          "0x0001000111111111111111111111111111111111111111110200",
          "extraData run 0 of length 2 overshoots");
    }

    @Test
    void repeatedExtraDataRunRejected() {
      assertRejected(
          "0x00020000021111111111111111111111111111111111111111010001 00".replace(" ", ""),
          "extraData run 1 repeats");
    }

    @Test
    void repeatedNonEmptyExtraDataRunRejected() {
      assertRejected(
          "0x0002000002111111111111111111111111111111111111111101 01aa 01 01aa".replace(" ", ""),
          "extraData run 1 repeats");
    }

    @Test
    void extraDataLongerThan32Rejected() {
      assertRejected(
          "0x0001000111111111111111111111111111111111111111110121" + "00".repeat(33),
          "extraData length 33 exceeds 32");
    }

    @Test
    void extraDataOf32Accepted() {
      final String extra = "0x" + "ab".repeat(32);
      final NativeBlockSpan span =
          decode(
              "0x00010001111111111111111111111111111111111111111101" + "20" + extra.substring(2));
      assertThat(span.blocks().getFirst().extraData()).isEqualTo(Bytes.fromHexString(extra));
    }

    @Test
    void truncatedBeneficiaryRejected() {
      assertRejected("0x0001000111", "truncated beneficiary");
    }

    @Test
    void truncatedExtraDataRejected() {
      assertRejected(
          "0x000100011111111111111111111111111111111111111111010201", "truncated extraData");
    }

    @Test
    void missingExtraDataColumnRejected() {
      assertRejected("0x00010001" + "11".repeat(20), "truncated varint extraData run length 0");
    }
  }

  @Nested
  class Transactions {

    // one beneficiary run of A, one extraData run of 0x
    private static final String ONE_BLOCK_META = "01" + "11".repeat(20) + "0100";

    @Test
    void transactionTotalAboveHalfRemainingRejectedBeforeAllocation() {
      // two declared transactions, three bytes left: floor(3 / 2) = 1
      assertRejected(
          "0x000102" + ONE_BLOCK_META + "010101",
          "2 transactions cannot fit in the 3 remaining bytes");
    }

    @Test
    void zeroTransactionLengthRejected() {
      assertRejected("0x000101" + ONE_BLOCK_META + "0000", "pure_transaction_lengths[0] is zero");
    }

    @Test
    void transactionLengthOvershootRejected() {
      assertRejected("0x000101" + ONE_BLOCK_META + "05aabbcc", "truncated");
    }

    @Test
    void transactionTrailingByteRejected() {
      assertRejected("0x000101" + ONE_BLOCK_META + "01aabb", "trailing bytes");
    }

    @Test
    void transactionCountSumOverflowsIsRejected() {
      // two counts of 2^32-1 each overflow int but not long; the half-remaining bound rejects them
      assertRejected(
          "0x0002ffffffff0fffffffff0f" + "02" + "1111111111111111111111111111111111111111" + "0200",
          "8589934590 transactions cannot fit");
    }

    @Test
    void transactionsAreAssignedToBlocksInOrder() {
      final Bytes t1 = Bytes.fromHexString("0x01aa");
      final Bytes t2 = Bytes.fromHexString("0x02bbbb");
      final Bytes t3 = Bytes.fromHexString("0x03");
      final NativeBlockSpan span =
          span(block(A, "0x", t1, t2), block(A, "0x"), block(B, "0x01", t3));
      final Bytes encoded = NativeBlockSpanV0Codec.encode(span);
      assertThat(encoded)
          .isEqualTo(
              Bytes.fromHexString(
                  "0x00"
                      + "03"
                      + "020001"
                      + "02"
                      + "11".repeat(20)
                      + "01"
                      + "22".repeat(20)
                      + "0200"
                      + "010101"
                      + "020301"
                      + "01aa"
                      + "02bbbb"
                      + "03"));
      assertThat(NativeBlockSpanV0Codec.decode(encoded)).isEqualTo(span);
      assertThat(span.transactionCount()).isEqualTo(3);
    }
  }

  @Nested
  class Schedule {

    @Test
    void productionRequiresMultipleOfSix() {
      final Bytes sevenBlocks =
          NativeBlockSpanV0Codec.encode(
              new NativeBlockSpan(Collections.nCopies(7, block(A, "0x"))));
      assertThat(NativeBlockSpanV0Codec.decode(sevenBlocks).blockCount()).isEqualTo(7);
      assertThatThrownBy(
              () -> NativeBlockSpanV0Codec.decode(sevenBlocks, PRODUCTION_POSITIONS_PER_INTERVAL))
          .isInstanceOf(PayloadDecodingException.class)
          .hasMessageContaining("block_count 7 is not a positive multiple of 6");
    }

    @Test
    void scheduleCheckRunsBeforeColumnsAreParsed() {
      // block_count 1 with a malformed body: the schedule check reports first
      assertThatThrownBy(() -> NativeBlockSpanV0Codec.decode(Bytes.fromHexString("0x0001ff"), 6))
          .hasMessageContaining("not a positive multiple of 6");
    }

    @Test
    void devNetworkOverrideAcceptsItsInterval() {
      final Bytes fiveBlocks =
          NativeBlockSpanV0Codec.encode(
              new NativeBlockSpan(Collections.nCopies(5, block(A, "0x"))));
      assertThat(NativeBlockSpanV0Codec.decode(fiveBlocks, 5).blockCount()).isEqualTo(5);
      assertThatThrownBy(() -> NativeBlockSpanV0Codec.requireOnSchedule(5, 6))
          .isInstanceOf(PayloadDecodingException.class);
      NativeBlockSpanV0Codec.requireOnSchedule(12, 6);
    }
  }

  @Nested
  class Encoder {

    @Test
    void rejectsEmptySpan() {
      assertThatThrownBy(() -> NativeBlockSpanV0Codec.encode(new NativeBlockSpan(List.of())))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyTransaction() {
      assertThatThrownBy(() -> NativeBlockSpanV0Codec.encode(span(block(A, "0x", Bytes.EMPTY))))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nativeBlockRejectsLongExtraData() {
      assertThatThrownBy(() -> block(A, "0x" + "00".repeat(33)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void alternatingMetadataRoundTrips() {
      final List<NativeBlock> blocks = new ArrayList<>();
      for (int i = 0; i < 12; i++) {
        blocks.add(
            block(
                i % 3 == 0 ? A : B,
                i % 2 == 0 ? "0x" : "0x" + "cd".repeat(i),
                i % 4 == 0 ? new Bytes[] {TX, Bytes.of(i + 1)} : new Bytes[0]));
      }
      final NativeBlockSpan span = new NativeBlockSpan(blocks);
      final Bytes encoded = NativeBlockSpanV0Codec.encode(span);
      assertThat(NativeBlockSpanV0Codec.decode(encoded, PRODUCTION_POSITIONS_PER_INTERVAL))
          .isEqualTo(span);
      assertThat(NativeBlockSpanV0Codec.encode(NativeBlockSpanV0Codec.decode(encoded)))
          .isEqualTo(encoded);
    }
  }

  private static NativeBlockSpan span(final NativeBlock... blocks) {
    return new NativeBlockSpan(List.of(blocks));
  }
}
