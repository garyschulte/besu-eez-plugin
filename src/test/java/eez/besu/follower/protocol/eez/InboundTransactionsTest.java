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
package eez.besu.follower.protocol.eez;

import static eez.besu.follower.protocol.eez.CoreVectors.ACTION;
import static eez.besu.follower.protocol.eez.CoreVectors.DEPLOYMENT;
import static eez.besu.follower.protocol.eez.CoreVectors.EEZ;
import static eez.besu.follower.protocol.eez.CoreVectors.ONE_ETHER;
import static eez.besu.follower.protocol.eez.CoreVectors.ROLLUP0_CHAIN_ID;
import static eez.besu.follower.protocol.eez.CoreVectors.ROLLUP_ID;
import static eez.besu.follower.protocol.eez.CoreVectors.SETTLEMENT;
import static eez.besu.follower.protocol.eez.CoreVectors.bytes;
import static eez.besu.follower.protocol.eez.CoreVectors.bytes32;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.TransactionType;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.encoding.EncodingContext;
import org.hyperledger.besu.ethereum.core.encoding.TransactionDecoder;

import java.math.BigInteger;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;

class InboundTransactionsTest {

  @Test
  void tagConstantsMatchSpec() {
    assertThat(Rollup0Constants.INBOUND_SOURCE_TAG)
        .isEqualTo(
            Bytes32.fromHexString(
                "0xa5463ebc06077538d400f54b653944302edcce2ec4d9c2675a764c6b403c567f"));
    assertThat(Rollup0Constants.UNSAFE_BLOCK_DOMAIN_TAG)
        .isEqualTo(
            Bytes32.fromHexString(
                "0xdb821d3d941bda3532841751ab0568201783ed2ad81cdc4f5474e73c0552a234"));
    assertThat(Rollup0Constants.SYSTEM_ADDRESS).isEqualTo(Transaction.PROTOCOL_DERIVED_SENDER);
  }

  @Test
  void sourceHashMatchesSolidityAbiEncode() {
    final Bytes32 domain = DEPLOYMENT.sourceDomain();
    assertThat(domain).isEqualTo(bytes32("sourceDomain"));
    assertThat(InboundSource.sourceHash(domain, SETTLEMENT, 0, bytes32("callHash")))
        .isEqualTo(bytes32("sourceHash"));
    assertThat(InboundSource.sourceHash(domain, SETTLEMENT, 1, bytes32("callHash")))
        .isNotEqualTo(bytes32("sourceHash"));
    assertThat(InboundSource.sourceDomain(1, BigInteger.ONE, EEZ, ROLLUP_ID, ROLLUP0_CHAIN_ID))
        .isNotEqualTo(domain);
  }

  @Test
  void selectorMatchesEezL2() {
    assertThat(InboundTransactions.EXECUTE_INCOMING_CROSS_CHAIN_CALL.selector())
        .isEqualTo(bytes("selector.executeIncomingCrossChainCall"));
  }

  @Test
  void inputMatchesTheDeliveryEezL2Executed() {
    final L2ExecutionEntry entry = InboundTransactions.l2Entry(ACTION, ROLLUP_ID);
    assertThat(entry.proxyEntryHash()).isEqualTo(bytes32("callHash"));
    assertThat(entry.rollingHash()).isEqualTo(bytes32("l2RollingHash"));
    assertThat(entry.incomingCalls())
        .containsExactly(
            new CrossChainCall(
                0,
                false,
                0,
                CoreVectors.SOURCE,
                0,
                CoreVectors.TARGET,
                ONE_ETHER,
                CoreVectors.DATA));
    assertThat(InboundTransactions.input(entry)).isEqualTo(bytes("l2Calldata"));
  }

  @Test
  void decodesInput() {
    final InboundTransactions.IncomingCall call =
        InboundTransactions.decodeInput(bytes("l2Calldata"));
    assertThat(call.entries()).containsExactly(InboundTransactions.l2Entry(ACTION, ROLLUP_ID));
    assertThat(call.staticEntries()).isEmpty();
  }

  @Test
  void nonZeroBracketGasIsBoundIntoTheL2EntryIdentity() {
    final InboundAction gassed =
        new InboundAction(
            0,
            CoreVectors.SOURCE,
            CoreVectors.TARGET,
            ONE_ETHER,
            50_000,
            CoreVectors.DATA,
            CoreVectors.RETURN_DATA);
    final L2ExecutionEntry entry = InboundTransactions.l2Entry(gassed, ROLLUP_ID);
    assertThat(gassed.callHash(ROLLUP_ID)).isEqualTo(bytes32("callHash"));
    assertThat(entry.proxyEntryHash())
        .isEqualTo(
            EezHashes.crossChainCallHash(
                false,
                CoreVectors.SOURCE,
                0,
                CoreVectors.TARGET,
                ROLLUP_ID,
                ONE_ETHER,
                50_000,
                CoreVectors.DATA));
    assertThat(entry.incomingCalls().getFirst().gas()).isEqualTo(50_000);
  }

  @Test
  void gasLimitIsCappedRemainingBlockGas() {
    assertThat(InboundTransactions.gasLimit(30_000_000)).isEqualTo(16_777_216);
    assertThat(InboundTransactions.gasLimit(16_777_216)).isEqualTo(16_777_216);
    assertThat(InboundTransactions.gasLimit(21_000)).isEqualTo(21_000);
    assertThat(InboundTransactions.gasLimit(0)).isZero();
    assertThatThrownBy(() -> InboundTransactions.gasLimit(-1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void derivedEnvelopeMatchesIndependentRlp() {
    final Transaction tx = InboundTransactions.derive(DEPLOYMENT, SETTLEMENT, ACTION, 30_000_000);
    assertThat(tx.getType()).isEqualTo(TransactionType.EEZ_INBOUND);
    assertThat(tx.getSender()).isEqualTo(Rollup0Constants.SYSTEM_ADDRESS);
    assertThat(tx.getSourceHash()).contains(bytes32("sourceHash"));
    assertThat(tx.getGasLimit()).isEqualTo(Rollup0Constants.INBOUND_GAS_CAP);
    assertThat(tx.getValue()).isEqualTo(Wei.of(ONE_ETHER));
    assertThat(tx.getTo()).contains(Rollup0Constants.DEFAULT_EEZ_L2_ADDRESS);

    final Bytes envelope = InboundTransactions.envelope(tx);
    assertThat(envelope).isEqualTo(bytes("inboundEnvelope"));
    assertThat(tx.getHash()).isEqualTo(Hash.wrap(bytes32("inboundTxHash")));
    assertThat(TransactionDecoder.decodeOpaqueBytes(envelope, EncodingContext.BLOCK_BODY))
        .isEqualTo(tx);
  }

  @Test
  void minimalEnvelopeKnownAnswer() {
    final Transaction tx =
        InboundTransactions.transaction(
            BigInteger.ONE,
            CoreVectors.word(1),
            21_000,
            Rollup0Constants.DEFAULT_EEZ_L2_ADDRESS,
            Wei.ZERO,
            Bytes.EMPTY);
    assertThat(InboundTransactions.envelope(tx)).isEqualTo(bytes("minimalEnvelope"));
  }

  @Test
  void rejectsGasAboveCap() {
    assertThatThrownBy(
            () ->
                InboundTransactions.transaction(
                    BigInteger.ONE,
                    Bytes32.ZERO,
                    Rollup0Constants.INBOUND_GAS_CAP + 1,
                    Rollup0Constants.DEFAULT_EEZ_L2_ADDRESS,
                    Wei.ZERO,
                    Bytes.EMPTY))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void zeroValueActionWithEmptyDataAndReturn() {
    final InboundAction action =
        new InboundAction(
            1,
            CoreVectors.SOURCE,
            CoreVectors.TARGET,
            BigInteger.ZERO,
            0,
            Bytes.EMPTY,
            Bytes.EMPTY);
    assertThat(action.callHash(ROLLUP_ID)).isEqualTo(bytes32("callHash2"));
    final Transaction tx = InboundTransactions.derive(DEPLOYMENT, SETTLEMENT, action, 1_000_000);
    assertThat(tx.getGasLimit()).isEqualTo(1_000_000);
    assertThat(tx.getValue()).isEqualTo(Wei.ZERO);
    final InboundTransactions.IncomingCall decoded =
        InboundTransactions.decodeInput(tx.getPayload());
    assertThat(decoded.entries()).hasSize(1);
    assertThat(decoded.entries().getFirst().returnData()).isEqualTo(Bytes.EMPTY);
    assertThat(decoded.entries().getFirst().rollingHash())
        .isEqualTo(
            EezHashes.callEnd(
                EezHashes.callBegin(
                    EezHashes.l2EntrySeed(bytes32("callHash2")), bytes32("callHash2")),
                true,
                Bytes.EMPTY));
    assertThat(tx.getSourceHash())
        .contains(
            InboundSource.sourceHash(
                DEPLOYMENT.sourceDomain(), SETTLEMENT, 1, bytes32("callHash2")));
    assertThat(List.of(tx.getPayload().slice(0, 4)))
        .containsExactly(bytes("selector.executeIncomingCrossChainCall"));
  }
}
