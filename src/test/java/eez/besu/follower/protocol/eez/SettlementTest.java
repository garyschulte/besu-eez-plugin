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

import static eez.besu.follower.protocol.eez.CoreVectors.BLOB_HASH;
import static eez.besu.follower.protocol.eez.CoreVectors.DEPLOYMENT;
import static eez.besu.follower.protocol.eez.CoreVectors.EEZ;
import static eez.besu.follower.protocol.eez.CoreVectors.H0;
import static eez.besu.follower.protocol.eez.CoreVectors.H1;
import static eez.besu.follower.protocol.eez.CoreVectors.H_PARENT;
import static eez.besu.follower.protocol.eez.CoreVectors.ONE_ETHER;
import static eez.besu.follower.protocol.eez.CoreVectors.ROLLUP_ID;
import static eez.besu.follower.protocol.eez.CoreVectors.SETTLEMENT;
import static eez.besu.follower.protocol.eez.CoreVectors.WRAPPER;
import static eez.besu.follower.protocol.eez.CoreVectors.address;
import static eez.besu.follower.protocol.eez.CoreVectors.bytes;
import static eez.besu.follower.protocol.eez.CoreVectors.bytes32;
import static eez.besu.follower.protocol.eez.CoreVectors.log;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import eez.besu.follower.protocol.eez.abi.Abi;
import eez.besu.follower.protocol.eez.abi.AbiDecodingException;

import org.hyperledger.besu.datatypes.Address;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;

class SettlementTest {

  private static final Address RELAYER = Address.fromHexString("0x1111");
  private final SettlementCalldataDecoder decoder =
      new SettlementCalldataDecoder(
          EEZ, WRAPPER, SettlementCalldataDecoder.DEFAULT_WRAPPER_FUNCTION_NAME);

  @Test
  void postAndVerifyBatchSelectorMatchesEez() {
    assertThat(SettlementCalldataDecoder.POST_AND_VERIFY_BATCH.selector())
        .isEqualTo(bytes("selector.postAndVerifyBatch"));
  }

  @Test
  void decodesWrapperSettlementCalldata() {
    final SettlementCalldataDecoder.SettlementCall call = decodeVector();
    assertThat(call.eezCaller()).isEqualTo(WRAPPER);
    final EezBatch batch = call.batch();

    assertThat(batch.blockNumber()).isEqualTo(Rollup0Constants.LATEST_CONTEXT_BLOCK_NUMBER);
    assertThat(batch.blobIndices()).containsExactly(BigInteger.ZERO);
    assertThat(batch.callData()).isEqualTo(Bytes.EMPTY);
    assertThat(batch.proofSystems()).containsExactly(address("proofSystem"));
    assertThat(batch.proofs()).containsExactly(Bytes.fromHexString("0102"));
    assertThat(batch.bindMsgSenderInPublicInput()).isTrue();
    assertThat(batch.rollupIdsWithProofSystems())
        .containsExactly(new EezBatch.RollupProofSystems(ROLLUP_ID, List.of(0L)));

    assertThat(Rollup0BatchProfile.violations(batch, ROLLUP_ID, 1)).isEmpty();
    assertThat(Rollup0BatchProfile.anchorUpdate(batch))
        .isEqualTo(new RollupUpdate(ROLLUP_ID, BigInteger.ZERO, H_PARENT, H0));
    final List<L1ExecutionEntry> deferred = Rollup0BatchProfile.deferredEntries(batch);
    assertThat(deferred).hasSize(1);
    assertThat(deferred.getFirst().proxyEntryHash()).isEqualTo(bytes32("callHash"));
    assertThat(deferred.getFirst().returnData()).isEqualTo(CoreVectors.RETURN_DATA);
    assertThat(deferred.getFirst().rollupUpdates())
        .containsExactly(new RollupUpdate(ROLLUP_ID, ONE_ETHER, H0, H1));

    assertThat(batch.entries().get(0).hash()).isEqualTo(bytes32("entryHash0"));
    assertThat(batch.entries().get(1).hash()).isEqualTo(bytes32("entryHash1"));
  }

  @Test
  void reEncodesToTheExactCalldata() {
    final Bytes calldata = bytes("settlementCalldata");
    final EezBatch batch = decodeVector().batch();
    assertThat(
            SettlementCalldataDecoder.batchFunction("submitCandidate")
                .encodeCall(List.of(batch.toAbi())))
        .isEqualTo(calldata);
  }

  @Test
  void decodesDirectEezCall() {
    final EezBatch batch = decodeVector().batch();
    final Bytes direct =
        SettlementCalldataDecoder.POST_AND_VERIFY_BATCH.encodeCall(List.of(batch.toAbi()));
    final SettlementCalldataDecoder.SettlementCall call =
        decoder.decode(RELAYER, EEZ, direct).orElseThrow();
    assertThat(call.batch()).isEqualTo(batch);
    assertThat(call.eezCaller()).isEqualTo(RELAYER);
  }

  @Test
  void ignoresUnrelatedTransactions() {
    final Bytes calldata = bytes("settlementCalldata");
    assertThat(decoder.decode(RELAYER, EEZ, calldata)).isEmpty();
    assertThat(decoder.decode(RELAYER, Address.fromHexString("0x22"), calldata)).isEmpty();
    assertThat(decoder.decode(RELAYER, WRAPPER, Bytes.fromHexString("0x12"))).isEmpty();
  }

  @Test
  void rejectsTruncatedBatch() {
    final Bytes calldata = bytes("settlementCalldata");
    assertThatThrownBy(
            () -> decoder.decode(RELAYER, WRAPPER, calldata.slice(0, calldata.size() - 64)))
        .isInstanceOf(AbiDecodingException.class);
  }

  @Test
  void resolvesBlobIndicesToVersionedHashes() {
    final EezBatch batch = decodeVector().batch();
    final Bytes32 other = Bytes32.fromHexString("0x01" + "22".repeat(31));
    assertThat(batch.selectBlobVersionedHashes(List.of(BLOB_HASH, other)))
        .containsExactly(BLOB_HASH);
    assertThatThrownBy(() -> batch.selectBlobVersionedHashes(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    // the V1 profile additionally requires every carried blob to be selected
    assertThat(Rollup0BatchProfile.violations(batch, ROLLUP_ID, 2)).isNotEmpty();
  }

  @Test
  void candidateCustomDataMatchesManager() {
    assertThat(PublicInputs.candidateCustomData(DEPLOYMENT, SETTLEMENT))
        .isEqualTo(bytes("customData"));
    assertThat(Rollup0Constants.CANDIDATE_DOMAIN_TAG)
        .isEqualTo(
            Bytes32.fromHexString(
                "0xba3ba53e31274af0c3f0f70c908597cbb1bc9a6a33006333feea258597f384b7"));
  }

  @Test
  void sharedPublicInputMatchesBatchPosted() {
    final SettlementCalldataDecoder.SettlementCall call = decodeVector();
    final EezBatch batch = call.batch();
    final Bytes32 shared =
        PublicInputs.sharedPublicInput(
            batch,
            batch.selectBlobVersionedHashes(List.of(BLOB_HASH)),
            List.of(PublicInputs.candidateCustomData(DEPLOYMENT, SETTLEMENT)),
            call.eezCaller());
    final EezEvent.BatchPosted posted =
        (EezEvent.BatchPosted)
            new EezEventDecoder(EEZ)
                .decode(EEZ, log("settlementLogs", 2).topics(), log("settlementLogs", 2).data())
                .orElseThrow();
    assertThat(shared).isEqualTo(posted.sharedPublicInput());

    // a different settlement context or submitter changes it
    assertThat(
            PublicInputs.sharedPublicInput(
                batch,
                List.of(BLOB_HASH),
                List.of(
                    PublicInputs.candidateCustomData(
                        DEPLOYMENT,
                        new SettlementContext(
                            SETTLEMENT.timestamp() + 12, SETTLEMENT.parentHash()))),
                call.eezCaller()))
        .isNotEqualTo(shared);
    assertThat(
            PublicInputs.sharedPublicInput(
                batch,
                List.of(BLOB_HASH),
                List.of(PublicInputs.candidateCustomData(DEPLOYMENT, SETTLEMENT)),
                RELAYER))
        .isNotEqualTo(shared);
  }

  @Test
  void publicInputsHashFoldsRollupKeys() {
    final Bytes32 shared = CoreVectors.word(1);
    final Bytes32 key = CoreVectors.word(0x100);
    final Bytes32 accumulator =
        Abi.keccak256(Bytes.concatenate(Bytes32.ZERO, CoreVectors.word(ROLLUP_ID), key));
    assertThat(
            PublicInputs.publicInputsHash(
                shared, List.of(new PublicInputs.RollupKey(ROLLUP_ID, key))))
        .isEqualTo(Abi.keccak256(Bytes.concatenate(shared, accumulator)));
    assertThat(PublicInputs.publicInputsHash(shared, List.of()))
        .isEqualTo(Abi.keccak256(Bytes.concatenate(shared, Bytes32.ZERO)));
  }

  @Test
  void profileRejectsNonConformingBatches() {
    final EezBatch batch = decodeVector().batch();
    assertThat(Rollup0BatchProfile.violations(batch, ROLLUP_ID + 1, 1)).isNotEmpty();
    assertThat(violations(withBlockNumber(batch, 0))).contains("blockNumber must be 2^64-1");
    assertThat(
            violations(
                new EezBatch(
                    batch.expectedRootPerRollup(),
                    batch.entries(),
                    batch.staticEntries(),
                    BigInteger.TWO,
                    BigInteger.ONE,
                    batch.proofSystems(),
                    batch.rollupIdsWithProofSystems(),
                    List.of(BigInteger.ONE),
                    Bytes.of(1),
                    batch.proofs(),
                    batch.blockNumber(),
                    batch.bindMsgSenderInPublicInput())))
        .contains(
            "callData must be empty",
            "blobIndices must be [0, m)",
            "immediateEntryCount must be 1",
            "immediateStaticEntryCount must be 0");

    final L1ExecutionEntry anchor = batch.entries().getFirst();
    final L1ExecutionEntry badAnchor =
        new L1ExecutionEntry(
            List.of(new RollupUpdate(ROLLUP_ID, BigInteger.ONE, H0, H0)),
            CoreVectors.word(1),
            List.of(),
            List.of(),
            anchor.rollingHash(),
            ROLLUP_ID,
            true,
            Bytes.of(1));
    final List<L1ExecutionEntry> entries = new ArrayList<>(batch.entries());
    entries.set(0, badAnchor);
    assertThat(violations(withEntries(batch, entries)))
        .contains(
            "anchor proxyEntryHash must be zero",
            "anchor must have no ether delta",
            "anchor H[0] must differ from Hparent",
            "anchor must succeed with empty return data");
  }

  private static List<String> violations(final EezBatch batch) {
    return Rollup0BatchProfile.violations(batch, ROLLUP_ID, 1);
  }

  private static EezBatch withBlockNumber(final EezBatch b, final long blockNumber) {
    return new EezBatch(
        b.expectedRootPerRollup(),
        b.entries(),
        b.staticEntries(),
        b.immediateEntryCount(),
        b.immediateStaticEntryCount(),
        b.proofSystems(),
        b.rollupIdsWithProofSystems(),
        b.blobIndices(),
        b.callData(),
        b.proofs(),
        blockNumber,
        b.bindMsgSenderInPublicInput());
  }

  private static EezBatch withEntries(final EezBatch b, final List<L1ExecutionEntry> entries) {
    return new EezBatch(
        b.expectedRootPerRollup(),
        entries,
        b.staticEntries(),
        b.immediateEntryCount(),
        b.immediateStaticEntryCount(),
        b.proofSystems(),
        b.rollupIdsWithProofSystems(),
        b.blobIndices(),
        b.callData(),
        b.proofs(),
        b.blockNumber(),
        b.bindMsgSenderInPublicInput());
  }

  private SettlementCalldataDecoder.SettlementCall decodeVector() {
    return decoder.decode(RELAYER, WRAPPER, bytes("settlementCalldata")).orElseThrow();
  }
}
