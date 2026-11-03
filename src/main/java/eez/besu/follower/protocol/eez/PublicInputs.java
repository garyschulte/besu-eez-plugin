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

import static eez.besu.follower.protocol.eez.AbiValues.uint64;

import eez.besu.follower.protocol.eez.abi.Abi;
import eez.besu.follower.protocol.eez.abi.AbiType;

import org.hyperledger.besu.datatypes.Address;

import java.util.ArrayList;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * The Rollup0 candidate domain (D.2) and the EEZ public-input fold ({@code
 * EEZ._verifyProofSystemBatch} at rev 84a5b52). Recomputing {@link #sharedPublicInput} from a
 * decoded batch and matching it against {@code BatchPosted.sharedPublicInput} authenticates the
 * decoded batch, its blob hashes and the context fields (10.2 steps 2 and 5).
 */
public final class PublicInputs {

  private static final AbiType BYTES32_ARRAY = AbiType.parse("bytes32[]");
  private static final AbiType.Tuple CUSTOM_DATA_TYPE =
      AbiType.parseTuple(
          "(bytes32,uint256,address,address,address,uint256,uint256,uint256,bytes32)");
  private static final AbiType.Tuple CUSTOM_DATA_KEY_TYPE = AbiType.parseTuple("(uint64,bytes)");
  private static final AbiType.Tuple ACCUMULATOR_TYPE =
      AbiType.parseTuple("(bytes32,uint64,bytes32)");

  private PublicInputs() {}

  /**
   * One rollup's verification key for one proof system.
   *
   * @param rollupId {@code uint64} rollup id
   * @param verificationKey the key the rollup contract returned
   */
  public record RollupKey(long rollupId, Bytes32 verificationKey) {}

  /**
   * The Rollup0 manager's {@code getCustomData(2^64 - 1)} result (D.2).
   *
   * @param deployment the configured Rollup0 identities
   * @param settlement the settlement block context
   * @return {@code abi.encode(tag, chainid, EEZ, manager, wrapper, rollupId, rollup0ChainId,
   *     timestamp, parentHash)}
   */
  public static Bytes candidateCustomData(
      final Rollup0Deployment deployment, final SettlementContext settlement) {
    return Abi.encode(
        CUSTOM_DATA_TYPE,
        List.of(
            Rollup0Constants.CANDIDATE_DOMAIN_TAG,
            deployment.settlementChainId(),
            deployment.eez(),
            deployment.manager(),
            deployment.settlementWrapper(),
            uint64(deployment.eezRollupId()),
            deployment.rollup0ChainId(),
            uint64(settlement.timestamp()),
            settlement.parentHash()));
  }

  /**
   * The batch's shared public input: {@code keccak256(abi.encode(entryHashes) ‖
   * abi.encode(staticEntryHashes) ‖ abi.encode(blobHashes) ‖ keccak256(callData) ‖
   * abi.encode(customDataHashes) ‖ boundSender)}, where {@code customDataHashes[r] =
   * keccak256(abi.encode(rollupId_r, customData_r))} and {@code boundSender} is packed as 20 bytes.
   *
   * @param batch the decoded batch
   * @param blobVersionedHashes {@link EezBatch#selectBlobVersionedHashes selected} versioned hashes
   * @param customData each participating rollup's custom data, in {@code rollupIdsWithProofSystems}
   *     order
   * @param eezCaller the {@code msg.sender} of {@code postAndVerifyBatch}
   * @return the shared public input emitted in {@code BatchPosted}
   */
  public static Bytes32 sharedPublicInput(
      final EezBatch batch,
      final List<Bytes32> blobVersionedHashes,
      final List<Bytes> customData,
      final Address eezCaller) {
    final List<EezBatch.RollupProofSystems> rollups = batch.rollupIdsWithProofSystems();
    if (customData.size() != rollups.size()) {
      throw new IllegalArgumentException("Need one custom data value per participating rollup");
    }
    final List<Bytes32> customDataHashes = new ArrayList<>(rollups.size());
    for (int r = 0; r < rollups.size(); r++) {
      customDataHashes.add(
          Abi.keccak256(
              Abi.encode(
                  CUSTOM_DATA_KEY_TYPE,
                  List.of(uint64(rollups.get(r).rollupId()), customData.get(r)))));
    }
    final Address boundSender = batch.bindMsgSenderInPublicInput() ? eezCaller : Address.ZERO;
    return Abi.keccak256(
        Bytes.concatenate(
            hashArray(batch.entries().stream().map(L1ExecutionEntry::hash).toList()),
            hashArray(batch.staticEntries().stream().map(L1StaticExecutionEntry::hash).toList()),
            hashArray(blobVersionedHashes),
            Abi.keccak256(batch.callData()),
            hashArray(customDataHashes),
            boundSender.getBytes()));
  }

  /**
   * The digest proof system {@code k} verifies: {@code keccak256(sharedPublicInput ‖ acc)}, where
   * {@code acc} folds {@code keccak256(abi.encode(acc, rollupId, vkey))} over the rollups selecting
   * {@code k}, in ascending rollup id order.
   *
   * @param sharedPublicInput the shared public input
   * @param keys the selecting rollups and their keys for proof system {@code k}, ascending
   * @return {@code publicInputsHash[k]}
   */
  public static Bytes32 publicInputsHash(
      final Bytes32 sharedPublicInput, final List<RollupKey> keys) {
    Bytes32 accumulator = Bytes32.ZERO;
    for (final RollupKey key : keys) {
      accumulator =
          Abi.keccak256(
              Abi.encode(
                  ACCUMULATOR_TYPE,
                  List.of(accumulator, uint64(key.rollupId()), key.verificationKey())));
    }
    return Abi.keccak256(Bytes.concatenate(sharedPublicInput, accumulator));
  }

  private static Bytes hashArray(final List<Bytes32> hashes) {
    return Abi.encodeSingle(BYTES32_ARRAY, List.<Object>copyOf(hashes));
  }
}
