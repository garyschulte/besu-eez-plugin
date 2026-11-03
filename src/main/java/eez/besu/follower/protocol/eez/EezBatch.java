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

import static eez.besu.follower.protocol.eez.AbiValues.bool;
import static eez.besu.follower.protocol.eez.AbiValues.bytes;
import static eez.besu.follower.protocol.eez.AbiValues.integer;
import static eez.besu.follower.protocol.eez.AbiValues.list;
import static eez.besu.follower.protocol.eez.AbiValues.listOf;
import static eez.besu.follower.protocol.eez.AbiValues.toU64;
import static eez.besu.follower.protocol.eez.AbiValues.u64List;
import static eez.besu.follower.protocol.eez.AbiValues.uint64;

import eez.besu.follower.protocol.eez.abi.AbiType;

import org.hyperledger.besu.datatypes.Address;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * The single argument of {@code EEZ.postAndVerifyBatch} ({@code
 * ProofSystemBatchPerVerificationEntries} in {@code IEEZ.sol}).
 *
 * @param expectedRootPerRollup optional live-root assertions
 * @param entries execution entries; the leading {@code immediateEntryCount} run in the posting tx
 * @param staticEntries static entries
 * @param immediateEntryCount {@code uint256} number of leading entries executed immediately
 * @param immediateStaticEntryCount {@code uint256} number of leading static entries made transient
 * @param proofSystems proof-system contracts, strictly increasing
 * @param rollupIdsWithProofSystems participating rollups and their proof-system subsets
 * @param blobIndices {@code uint256} positions in the posting transaction's blob list
 * @param callData batch-scoped calldata folded into the public input
 * @param proofs one proof per proof system
 * @param blockNumber {@code uint64} L1 context selector; {@code -1L} is the {@code 2^64-1} sentinel
 * @param bindMsgSenderInPublicInput whether EEZ folds its {@code msg.sender} into the public input
 */
public record EezBatch(
    List<ExpectedRoot> expectedRootPerRollup,
    List<L1ExecutionEntry> entries,
    List<L1StaticExecutionEntry> staticEntries,
    BigInteger immediateEntryCount,
    BigInteger immediateStaticEntryCount,
    List<Address> proofSystems,
    List<RollupProofSystems> rollupIdsWithProofSystems,
    List<BigInteger> blobIndices,
    Bytes callData,
    List<Bytes> proofs,
    long blockNumber,
    boolean bindMsgSenderInPublicInput) {

  /** The Solidity tuple type. */
  public static final AbiType.Tuple TYPE =
      AbiType.parseTuple(
          "("
              + ExpectedRoot.TYPE.canonical()
              + "[],"
              + L1ExecutionEntry.TYPE.canonical()
              + "[],"
              + L1StaticExecutionEntry.TYPE.canonical()
              + "[],uint256,uint256,address[],"
              + RollupProofSystems.TYPE.canonical()
              + "[],uint256[],bytes,bytes[],uint64,bool)");

  /** Copies the lists. */
  public EezBatch {
    expectedRootPerRollup = List.copyOf(expectedRootPerRollup);
    entries = List.copyOf(entries);
    staticEntries = List.copyOf(staticEntries);
    proofSystems = List.copyOf(proofSystems);
    rollupIdsWithProofSystems = List.copyOf(rollupIdsWithProofSystems);
    blobIndices = List.copyOf(blobIndices);
    proofs = List.copyOf(proofs);
  }

  /**
   * A participating rollup and the indices of the proof systems it is verified against ({@code
   * RollupIdWithProofSystems}).
   *
   * @param rollupId {@code uint64} rollup id
   * @param proofSystemIndexes {@code uint64} indices into {@link EezBatch#proofSystems()}
   */
  public record RollupProofSystems(long rollupId, List<Long> proofSystemIndexes) {

    /** {@code (uint64,uint64[])}. */
    public static final AbiType.Tuple TYPE = AbiType.parseTuple("(uint64,uint64[])");

    /** Copies the list. */
    public RollupProofSystems {
      proofSystemIndexes = List.copyOf(proofSystemIndexes);
    }

    /** Returns the ABI value of this struct. */
    public List<Object> toAbi() {
      return List.of(
          uint64(rollupId), proofSystemIndexes.stream().<Object>map(AbiValues::uint64).toList());
    }

    /**
     * Builds the struct from its decoded ABI value.
     *
     * @param v the decoded tuple
     * @return the struct
     */
    public static RollupProofSystems fromAbi(final List<?> v) {
      return new RollupProofSystems(toU64(v.get(0)), u64List(v.get(1)));
    }
  }

  /**
   * Resolves {@link #blobIndices()} against the posting transaction's blob versioned hashes, in
   * batch order, as {@code BLOBHASH(blobIndices[i])} does.
   *
   * @param transactionVersionedHashes the posting transaction's {@code blob_versioned_hashes}
   * @return the selected versioned hashes, one per blob index
   * @throws IllegalArgumentException if an index has no blob in the transaction
   */
  public List<Bytes32> selectBlobVersionedHashes(final List<Bytes32> transactionVersionedHashes) {
    final List<Bytes32> selected = new ArrayList<>(blobIndices.size());
    for (final BigInteger index : blobIndices) {
      if (index.compareTo(BigInteger.valueOf(transactionVersionedHashes.size())) >= 0) {
        throw new IllegalArgumentException(
            "Blob index "
                + index
                + " out of range for "
                + transactionVersionedHashes.size()
                + " blobs");
      }
      selected.add(transactionVersionedHashes.get(index.intValueExact()));
    }
    return selected;
  }

  /** Returns the ABI value of this struct. */
  public List<Object> toAbi() {
    return List.of(
        AbiValues.toAbi(expectedRootPerRollup, ExpectedRoot::toAbi),
        AbiValues.toAbi(entries, L1ExecutionEntry::toAbi),
        AbiValues.toAbi(staticEntries, L1StaticExecutionEntry::toAbi),
        immediateEntryCount,
        immediateStaticEntryCount,
        List.<Object>copyOf(proofSystems),
        AbiValues.toAbi(rollupIdsWithProofSystems, RollupProofSystems::toAbi),
        List.<Object>copyOf(blobIndices),
        callData,
        List.<Object>copyOf(proofs),
        uint64(blockNumber),
        bindMsgSenderInPublicInput);
  }

  /**
   * Builds the struct from its decoded ABI value.
   *
   * @param v the decoded tuple
   * @return the struct
   */
  public static EezBatch fromAbi(final List<?> v) {
    return new EezBatch(
        listOf(v.get(0), ExpectedRoot::fromAbi),
        listOf(v.get(1), L1ExecutionEntry::fromAbi),
        listOf(v.get(2), L1StaticExecutionEntry::fromAbi),
        integer(v.get(3)),
        integer(v.get(4)),
        list(v.get(5)).stream().map(AbiValues::address).toList(),
        listOf(v.get(6), RollupProofSystems::fromAbi),
        list(v.get(7)).stream().map(AbiValues::integer).toList(),
        bytes(v.get(8)),
        list(v.get(9)).stream().map(AbiValues::bytes).toList(),
        toU64(v.get(10)),
        bool(v.get(11)));
  }
}
