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

import static eez.besu.follower.protocol.eez.CoreVectors.EEZ;
import static eez.besu.follower.protocol.eez.CoreVectors.H0;
import static eez.besu.follower.protocol.eez.CoreVectors.H1;
import static eez.besu.follower.protocol.eez.CoreVectors.ONE_ETHER;
import static eez.besu.follower.protocol.eez.CoreVectors.ROLLUP_ID;
import static eez.besu.follower.protocol.eez.CoreVectors.bytes32;
import static eez.besu.follower.protocol.eez.CoreVectors.log;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import eez.besu.follower.protocol.eez.abi.Abi;
import eez.besu.follower.protocol.eez.abi.AbiDecodingException;

import org.hyperledger.besu.datatypes.Address;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;

class EezEventDecoderTest {

  private final EezEventDecoder decoder = new EezEventDecoder(EEZ);

  @Test
  void topicsMatchSoliditySignatures() {
    assertThat(EezEventDecoder.BATCH_POSTED.topic0())
        .isEqualTo(topic("BatchPosted(bytes32,uint64[])"));
    assertThat(EezEventDecoder.EXECUTION_CONSUMED.topic0())
        .isEqualTo(topic("ExecutionConsumed(bytes32,uint64,uint256)"));
    assertThat(EezEventDecoder.L2_EXECUTION_PERFORMED.topic0())
        .isEqualTo(topic("L2ExecutionPerformed(uint64,bytes32,uint256)"));
    assertThat(EezEventDecoder.L2_TX_SKIPPED.topic0())
        .isEqualTo(topic("L2TxSkipped(uint256,bytes)"));
    assertThat(EezEventDecoder.ROOT_UPDATED.topic0())
        .isEqualTo(topic("RootUpdated(uint64,bytes32)"));
    assertThat(EezEventDecoder.ROLLUP_CREATED.topic0())
        .isEqualTo(topic("RollupCreated(uint64,address,bytes32)"));
  }

  @Test
  void decodesSettlementLogs() {
    // anchor applied during postAndVerifyBatch: L2ExecutionPerformed(rid, H[0], 0)
    assertThat(decode(log("settlementLogs", 0)))
        .contains(new EezEvent.L2ExecutionPerformed(ROLLUP_ID, H0, BigInteger.ZERO));
    // EntryExecuted is not one of the follower's events
    assertThat(decode(log("settlementLogs", 1))).isEmpty();
    final EezEvent.BatchPosted posted =
        (EezEvent.BatchPosted) decode(log("settlementLogs", 2)).orElseThrow();
    assertThat(posted.rollupIds()).containsExactly(ROLLUP_ID);
    assertThat(posted.sharedPublicInput())
        .isEqualTo(Bytes32.wrap(log("settlementLogs", 2).data().slice(0, 32)));
  }

  @Test
  void decodesTriggerLogs() {
    // CrossChainCallExecuted is skipped
    assertThat(decode(log("triggerLogs", 0))).isEmpty();
    // successful action k = 1 consumes cursor k - 1 = 0
    assertThat(decode(log("triggerLogs", 1)))
        .contains(new EezEvent.ExecutionConsumed(bytes32("callHash"), ROLLUP_ID, BigInteger.ZERO));
    assertThat(decode(log("triggerLogs", 2)))
        .contains(new EezEvent.L2ExecutionPerformed(ROLLUP_ID, H1, ONE_ETHER));
    assertThat(decode(log("triggerLogs", 3))).isEmpty();
  }

  @Test
  void ignoresOtherEmitters() {
    final CoreVectors.Log consumed = log("triggerLogs", 1);
    assertThat(decoder.decode(Address.fromHexString("0x01"), consumed.topics(), consumed.data()))
        .isEmpty();
    assertThat(decoder.decode(EEZ, List.of(), Bytes.EMPTY)).isEmpty();
  }

  @Test
  void decodesEventsWithoutRecordedVectors() {
    final Bytes32 rollup = CoreVectors.word(ROLLUP_ID);
    final Bytes revert = Bytes.fromHexString("0x08c379a0");
    assertThat(
            decoder.decode(
                EEZ,
                List.of(EezEventDecoder.L2_TX_SKIPPED.topic0(), Bytes32.ZERO),
                Bytes.concatenate(
                    CoreVectors.word(32), CoreVectors.word(4), Bytes32.rightPad(revert))))
        .contains(new EezEvent.L2TxSkipped(BigInteger.ZERO, revert));
    assertThat(decoder.decode(EEZ, List.of(EezEventDecoder.ROOT_UPDATED.topic0(), rollup), H1))
        .contains(new EezEvent.RootUpdated(ROLLUP_ID, H1));
    assertThat(
            decoder.decode(
                EEZ,
                List.of(
                    EezEventDecoder.ROLLUP_CREATED.topic0(),
                    rollup,
                    Bytes32.leftPad(CoreVectors.MANAGER.getBytes())),
                CoreVectors.H_PARENT))
        .contains(new EezEvent.RollupCreated(ROLLUP_ID, CoreVectors.MANAGER, CoreVectors.H_PARENT));
  }

  @Test
  void rejectsMalformedKnownEvents() {
    final CoreVectors.Log consumed = log("triggerLogs", 1);
    assertThatThrownBy(() -> decoder.decode(EEZ, consumed.topics().subList(0, 3), consumed.data()))
        .isInstanceOf(AbiDecodingException.class);
    final CoreVectors.Log performed = log("triggerLogs", 2);
    assertThatThrownBy(() -> decoder.decode(EEZ, performed.topics(), performed.data().slice(0, 32)))
        .isInstanceOf(AbiDecodingException.class);
  }

  private Optional<EezEvent> decode(final CoreVectors.Log log) {
    return decoder.decode(log.emitter(), log.topics(), log.data());
  }

  private static Bytes32 topic(final String signature) {
    return Abi.keccak256(Bytes.wrap(signature.getBytes(StandardCharsets.US_ASCII)));
  }
}
