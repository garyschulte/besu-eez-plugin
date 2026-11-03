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

import eez.besu.follower.protocol.codec.EezMessage.Call;
import eez.besu.follower.protocol.codec.EezMessage.ChainOperation;
import eez.besu.follower.protocol.codec.EezMessage.CloseBlobStream;
import eez.besu.follower.protocol.codec.EezMessage.FinishCrossChainTransaction;
import eez.besu.follower.protocol.codec.EezMessage.InitiateCrossChainTransaction;
import eez.besu.follower.protocol.codec.EezMessage.ReturnFail;
import eez.besu.follower.protocol.codec.EezMessage.ReturnSuccess;
import eez.besu.follower.protocol.codec.EezMessage.Revert;
import eez.besu.follower.protocol.codec.EezMessage.Snapshot;
import eez.besu.follower.protocol.codec.EezMessage.StaticCall;
import eez.besu.follower.protocol.codec.EezStreamEncoder.EncodedStream;

import org.hyperledger.besu.datatypes.Address;

import java.util.ArrayList;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.Test;

/** Rollup0 D.4.1 / G.6 profile, including end-to-end decoding from self-generated blobs. */
class Rollup0ProfileTest {

  private static final long ROLLUP0 = 7;
  private static final Address FROM =
      Address.fromHexString("0x00000000000000000000000000000000000a11ce");
  private static final Address TO =
      Address.fromHexString("0xee50000000000000000000000000000000000000");
  // D.4.4 vector 1: six empty blocks
  private static final Bytes OPERATIONS =
      Bytes.fromHexString("0x00060000000000000600000000000000000000000000000000000000000600");

  private static final EezMessage OPEN = new InitiateCrossChainTransaction(0, Bytes.EMPTY);
  private static final EezMessage FINISH = new FinishCrossChainTransaction();
  private static final EezMessage CLOSE = new CloseBlobStream();

  private static ChainOperation chainOp() {
    return new ChainOperation(ROLLUP0, OPERATIONS);
  }

  private static Call call(final int n) {
    return new Call(ROLLUP0, FROM, TO, UInt256.valueOf(n), 100_000 + n, Bytes.of(n));
  }

  private static List<EezMessage> bracket(final int n, final boolean success) {
    return List.of(
        OPEN,
        call(n),
        success ? new ReturnSuccess(Bytes.of(0xa0 + n)) : new ReturnFail(Bytes.of(0xf0 + n)),
        FINISH);
  }

  private static List<EezMessage> stream(final List<List<EezMessage>> brackets) {
    final List<EezMessage> messages = new ArrayList<>();
    messages.add(chainOp());
    brackets.forEach(messages::addAll);
    messages.add(CLOSE);
    return messages;
  }

  private static List<EezMessage> with(
      final List<EezMessage> base, final int index, final EezMessage replacement) {
    final List<EezMessage> copy = new ArrayList<>(base);
    copy.set(index, replacement);
    return copy;
  }

  private static void assertRejected(final List<EezMessage> messages, final String reason) {
    assertThatThrownBy(() -> Rollup0Profile.validate(messages, ROLLUP0))
        .isInstanceOf(PayloadDecodingException.class)
        .hasMessageContaining(reason);
  }

  @Test
  void catchUpCandidateHasEmptyManifest() {
    final Rollup0Candidate candidate = Rollup0Profile.validate(stream(List.of()), ROLLUP0);
    assertThat(candidate.operations()).isEqualTo(OPERATIONS);
    assertThat(candidate.manifest()).isEmpty();
    assertThat(candidate.successCount()).isZero();
  }

  @Test
  void decodesBlobsEndToEnd() {
    final List<EezMessage> messages =
        stream(List.of(bracket(0, true), bracket(1, true), bracket(2, false)));
    final EncodedStream encoded = EezStreamEncoder.encode(messages);
    assertThat(encoded.callData()).isEqualTo(Bytes.EMPTY);

    final Rollup0Candidate candidate =
        Rollup0Profile.decode(encoded.blobs(), encoded.callData(), ROLLUP0);

    assertThat(candidate.operations()).isEqualTo(OPERATIONS);
    assertThat(NativeBlockSpanV0Codec.decode(candidate.operations()).blockCount()).isEqualTo(6);
    assertThat(candidate.manifest())
        .containsExactly(
            new ActionBracket(
                0, FROM, TO, UInt256.valueOf(0), 100_000, Bytes.of(0), true, Bytes.of(0xa0)),
            new ActionBracket(
                1, FROM, TO, UInt256.valueOf(1), 100_001, Bytes.of(1), true, Bytes.of(0xa1)),
            new ActionBracket(
                2, FROM, TO, UInt256.valueOf(2), 100_002, Bytes.of(2), false, Bytes.of(0xf2)));
    assertThat(candidate.successCount()).isEqualTo(2);
  }

  @Test
  void eezFormatViolationRejectsBeforeProfile() {
    final EncodedStream encoded = EezStreamEncoder.encode(List.of(chainOp()));
    assertThatThrownBy(() -> Rollup0Profile.decode(encoded.blobs(), Bytes.EMPTY, ROLLUP0))
        .isInstanceOf(PayloadDecodingException.class)
        .hasMessageContaining("invalid message type 0");
  }

  @Test
  void zeroBlobBatchIsNotARollup0Candidate() {
    final Bytes callData = EezStreamEncoder.encodeCallDataOnly(List.of(chainOp()));
    assertThatThrownBy(() -> Rollup0Profile.decode(List.of(), callData, ROLLUP0))
        .isInstanceOf(PayloadDecodingException.class)
        .hasMessageContaining("must end with CloseBlobStream");
  }

  @Test
  void rejectsMissingChainOperation() {
    assertRejected(List.of(CLOSE), "first message must be ChainOperation, found CLOSE_BLOB_STREAM");
    assertRejected(List.of(), "first message must be ChainOperation, found nothing");
  }

  @Test
  void rejectsBracketBeforeChainOperation() {
    final List<EezMessage> messages = new ArrayList<>(bracket(0, true));
    messages.add(chainOp());
    messages.add(CLOSE);
    assertRejected(messages, "first message must be ChainOperation");
  }

  @Test
  void rejectsForeignChainOperation() {
    assertRejected(
        with(stream(List.of()), 0, new ChainOperation(8, OPERATIONS)),
        "ChainOperation chain_id 8 is not the Rollup0 EEZ rollup id 7");
  }

  @Test
  void rejectsSecondChainOperation() {
    final List<EezMessage> messages = stream(List.of());
    messages.add(1, chainOp());
    assertRejected(
        messages, "message 1 (CHAIN_OPERATION) does not start a complete action bracket");
  }

  @Test
  void rejectsChainOperationAfterBracket() {
    final List<EezMessage> messages = stream(List.of(bracket(0, true)));
    messages.add(5, chainOp());
    assertRejected(messages, "message 5 (CHAIN_OPERATION)");
  }

  @Test
  void rejectsChainOperationInBracketPosition() {
    final List<EezMessage> messages = stream(List.of(bracket(0, true), bracket(1, true)));
    messages.add(5, chainOp());
    assertRejected(
        messages, "bracket 1 message 5 is CHAIN_OPERATION, expected InitiateCrossChainTransaction");
  }

  @Test
  void rejectsMissingCloseBlobStream() {
    final List<EezMessage> messages = stream(List.of(bracket(0, true)));
    messages.removeLast();
    assertRejected(messages, "must end with CloseBlobStream");
    assertRejected(List.of(chainOp()), "must end with CloseBlobStream");
  }

  @Test
  void rejectsMessagesInCallDataTail() {
    final List<EezMessage> messages = stream(List.of());
    messages.addAll(bracket(0, true));
    assertRejected(messages, "must end with CloseBlobStream");
  }

  @Test
  void rejectsNonL1Initiate() {
    assertRejected(
        with(
            stream(List.of(bracket(0, true))),
            1,
            new InitiateCrossChainTransaction(ROLLUP0, Bytes.EMPTY)),
        "bracket 0 InitiateCrossChainTransaction chain_id 7 is not 0");
  }

  @Test
  void rejectsNonEmptyTxData() {
    assertRejected(
        with(
            stream(List.of(bracket(0, true))),
            1,
            new InitiateCrossChainTransaction(0, Bytes.of(0))),
        "tx_data is not empty (1 bytes)");
  }

  @Test
  void rejectsCallToAnotherChain() {
    assertRejected(
        with(
            stream(List.of(bracket(0, true))),
            2,
            new Call(3, FROM, TO, UInt256.ZERO, 0, Bytes.EMPTY)),
        "bracket 0 Call to_chain 3 is not the Rollup0 EEZ rollup id 7");
  }

  @Test
  void rejectsStaticCall() {
    assertRejected(
        with(
            stream(List.of(bracket(0, true))),
            2,
            new StaticCall(ROLLUP0, FROM, TO, 0, Bytes.EMPTY)),
        "bracket 0 message 2 is STATIC_CALL, expected Call");
  }

  @Test
  void rejectsNestedCall() {
    final List<EezMessage> messages =
        stream(
            List.of(
                List.of(
                    OPEN,
                    call(0),
                    call(1),
                    new ReturnSuccess(Bytes.EMPTY),
                    new ReturnSuccess(Bytes.EMPTY),
                    FINISH)));
    assertRejected(messages, "bracket 0 message 3 is CALL, expected ReturnSuccess or ReturnFail");
  }

  @Test
  void rejectsSecondSiblingCall() {
    final List<EezMessage> messages =
        stream(
            List.of(
                List.of(
                    OPEN,
                    call(0),
                    new ReturnSuccess(Bytes.EMPTY),
                    call(1),
                    new ReturnSuccess(Bytes.EMPTY),
                    FINISH)));
    assertRejected(messages, "bracket 0 message 4 is CALL, expected FinishCrossChainTransaction");
  }

  @Test
  void rejectsSnapshotAndRevert() {
    final List<EezMessage> messages =
        stream(
            List.of(
                List.of(
                    OPEN,
                    new Snapshot(),
                    call(0),
                    new ReturnSuccess(Bytes.EMPTY),
                    new Revert(),
                    FINISH)));
    assertRejected(messages, "bracket 0 message 2 is SNAPSHOT, expected Call");
  }

  @Test
  void rejectsBracketAfterReturnFail() {
    assertRejected(
        stream(List.of(bracket(0, false), bracket(1, true))),
        "message 5 (INITIATE_CROSS_CHAIN_TRANSACTION) follows a ReturnFail bracket");
  }

  @Test
  void acceptsTerminalReturnFailOnly() {
    final Rollup0Candidate candidate =
        Rollup0Profile.validate(stream(List.of(bracket(0, false))), ROLLUP0);
    assertThat(candidate.manifest()).hasSize(1);
    assertThat(candidate.manifest().getFirst().success()).isFalse();
    assertThat(candidate.successCount()).isZero();
  }

  @Test
  void unsignedRollupIdsCompareExactly() {
    final long hugeId = -2L; // 2^64 - 2
    final List<EezMessage> messages =
        List.of(
            new ChainOperation(hugeId, OPERATIONS),
            OPEN,
            new Call(hugeId, FROM, TO, UInt256.ZERO, -1L, Bytes.EMPTY),
            new ReturnSuccess(Bytes.EMPTY),
            FINISH,
            CLOSE);
    final EncodedStream encoded = EezStreamEncoder.encode(messages);
    final Rollup0Candidate candidate =
        Rollup0Profile.decode(encoded.blobs(), encoded.callData(), hugeId);
    assertThat(Long.toUnsignedString(candidate.manifest().getFirst().gas()))
        .isEqualTo("18446744073709551615");
    assertThatThrownBy(() -> Rollup0Profile.validate(messages, ROLLUP0))
        .hasMessageContaining("chain_id 18446744073709551614");
  }
}
