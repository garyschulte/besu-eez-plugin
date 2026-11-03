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

import org.hyperledger.besu.datatypes.Address;

import java.math.BigInteger;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * The L1 {@code EEZ} events a Rollup0 follower reads (ch. 10), decoded by {@link EezEventDecoder}.
 * Fields named {@code root} carry a Rollup0 block hash for Rollup0 (D.3).
 */
public sealed interface EezEvent {

  /**
   * {@code BatchPosted(bytes32 sharedPublicInput, uint64[] rollupIds)}: a publication attempt; not
   * by itself proof that the anchor applied (ch. 5).
   *
   * @param sharedPublicInput the batch's shared public input
   * @param rollupIds participating rollups, ascending
   */
  record BatchPosted(Bytes32 sharedPublicInput, List<Long> rollupIds) implements EezEvent {
    /** Copies the list. */
    public BatchPosted {
      rollupIds = List.copyOf(rollupIds);
    }
  }

  /**
   * {@code ExecutionConsumed(bytes32 indexed crossChainCallHash, uint64 indexed rollupId, uint256
   * indexed entryQueueIndex)}: a deferred entry was consumed; successful action {@code k} has
   * {@code entryQueueIndex = k - 1} (10.1).
   *
   * @param crossChainCallHash the consumed entry's call hash
   * @param rollupId the destination rollup
   * @param entryQueueIndex the consumed queue position (the cursor)
   */
  record ExecutionConsumed(Bytes32 crossChainCallHash, long rollupId, BigInteger entryQueueIndex)
      implements EezEvent {}

  /**
   * {@code L2ExecutionPerformed(uint64 indexed rollupId, bytes32 newRoot, uint256 etherBalance)}:
   * one {@code RollupUpdate} was applied.
   *
   * @param rollupId the updated rollup
   * @param newRoot the new commitment, a Rollup0 block hash {@code H[k]}
   * @param etherBalance the rollup's ether balance after the update
   */
  record L2ExecutionPerformed(long rollupId, Bytes32 newRoot, BigInteger etherBalance)
      implements EezEvent {}

  /**
   * {@code L2TxSkipped(uint256 indexed entryIndex, bytes revertData)}: an immediate L2 transaction
   * entry (for Rollup0, the anchor) failed and was skipped.
   *
   * @param entryIndex the batch entry index
   * @param revertData the revert payload
   */
  record L2TxSkipped(BigInteger entryIndex, Bytes revertData) implements EezEvent {}

  /**
   * {@code RootUpdated(uint64 indexed rollupId, bytes32 newRoot)}: the rollup contract replaced the
   * root outside settlement (the manager escape, 10.6).
   *
   * @param rollupId the rollup
   * @param newRoot the new root
   */
  record RootUpdated(long rollupId, Bytes32 newRoot) implements EezEvent {}

  /**
   * {@code RollupCreated(uint64 indexed rollupId, address indexed rollupContract, bytes32
   * initialRoot)}: a rollup was registered.
   *
   * @param rollupId the assigned id
   * @param rollupContract the registered manager
   * @param initialRoot the initial root (Rollup0 genesis block hash)
   */
  record RollupCreated(long rollupId, Address rollupContract, Bytes32 initialRoot)
      implements EezEvent {}
}
