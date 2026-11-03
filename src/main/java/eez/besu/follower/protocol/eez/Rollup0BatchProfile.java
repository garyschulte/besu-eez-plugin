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

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.apache.tuweni.bytes.Bytes32;

/**
 * The candidate-protocol-V1 batch profile (7.3, D.2.1), expressed over the eez-core rev 84a5b52
 * batch fields: the draft's {@code transientExecutionEntryCount}/{@code transientLookupCallCount}
 * are {@code immediateEntryCount}/{@code immediateStaticEntryCount}, and the draft's {@code
 * crossProofSystemInteractions} field no longer exists.
 */
public final class Rollup0BatchProfile {

  private Rollup0BatchProfile() {}

  /**
   * Lists every way a decoded batch violates the V1 profile.
   *
   * @param batch the decoded batch
   * @param rollup0EezRollupId Rollup0's EEZ rollup id
   * @param transactionBlobCount number of blobs carried by the settlement transaction
   * @return human-readable violations; empty when the batch conforms
   */
  public static List<String> violations(
      final EezBatch batch, final long rollup0EezRollupId, final int transactionBlobCount) {
    final List<String> violations = new ArrayList<>();
    if (batch.rollupIdsWithProofSystems().size() != 1
        || batch.rollupIdsWithProofSystems().getFirst().rollupId() != rollup0EezRollupId) {
      violations.add("batch must contain exactly Rollup0");
    }
    if (batch.blockNumber() != Rollup0Constants.LATEST_CONTEXT_BLOCK_NUMBER) {
      violations.add("blockNumber must be 2^64-1");
    }
    if (!batch.callData().isEmpty()) {
      violations.add("callData must be empty");
    }
    final List<BigInteger> indices = batch.blobIndices();
    if (indices.isEmpty() || indices.size() != transactionBlobCount) {
      violations.add(
          "blobIndices must select all " + transactionBlobCount + " blobs, got " + indices.size());
    }
    for (int i = 0; i < indices.size(); i++) {
      if (!indices.get(i).equals(BigInteger.valueOf(i))) {
        violations.add("blobIndices must be [0, m)");
        break;
      }
    }
    if (!batch.immediateEntryCount().equals(BigInteger.ONE)) {
      violations.add("immediateEntryCount must be 1");
    }
    if (batch.immediateStaticEntryCount().signum() != 0) {
      violations.add("immediateStaticEntryCount must be 0");
    }
    if (batch.entries().isEmpty()) {
      violations.add("missing anchor entry");
    } else {
      anchorViolations(batch.entries().getFirst(), rollup0EezRollupId, violations);
    }
    for (final L1ExecutionEntry entry : batch.entries()) {
      if (entry.destinationRollupId() != rollup0EezRollupId
          || entry.rollupUpdates().stream().anyMatch(u -> u.rollupId() != rollup0EezRollupId)) {
        violations.add("entry routes or updates a network other than Rollup0");
      }
    }
    for (final L1StaticExecutionEntry entry : batch.staticEntries()) {
      if (entry.destinationRollupId() != rollup0EezRollupId
          || entry.expectedRoots().stream().anyMatch(r -> r.rollupId() != rollup0EezRollupId)) {
        violations.add("static entry targets a network other than Rollup0");
      }
    }
    if (batch.expectedRootPerRollup().stream().anyMatch(r -> r.rollupId() != rollup0EezRollupId)) {
      violations.add("root assertion for a network other than Rollup0");
    }
    return violations;
  }

  /**
   * Returns the anchor's single Rollup0 update ({@code Hparent -> H[0]}). Call only on a batch
   * without {@link #violations violations}.
   *
   * @param batch a conforming batch
   * @return the anchor update
   */
  public static RollupUpdate anchorUpdate(final EezBatch batch) {
    return batch.entries().getFirst().rollupUpdates().getFirst();
  }

  /**
   * Returns the deferred entries published to Rollup0's queue, in queue order: successful action
   * {@code k} is consumed at cursor {@code k - 1} (10.1).
   *
   * @param batch a conforming batch
   * @return the entries after the anchor
   */
  public static List<L1ExecutionEntry> deferredEntries(final EezBatch batch) {
    return batch.entries().subList(1, batch.entries().size());
  }

  private static void anchorViolations(
      final L1ExecutionEntry anchor, final long rollup0EezRollupId, final List<String> violations) {
    if (!anchor.proxyEntryHash().equals(Bytes32.ZERO)) {
      violations.add("anchor proxyEntryHash must be zero");
    }
    if (anchor.rollupUpdates().size() != 1) {
      violations.add("anchor must have exactly one state update");
    } else {
      final RollupUpdate update = anchor.rollupUpdates().getFirst();
      if (update.rollupId() != rollup0EezRollupId) {
        violations.add("anchor must update Rollup0");
      }
      if (update.etherDelta().signum() != 0) {
        violations.add("anchor must have no ether delta");
      }
      if (update.currentRoot().equals(update.newRoot())) {
        violations.add("anchor H[0] must differ from Hparent");
      }
    }
    if (!anchor.l2ToL1Calls().isEmpty() || !anchor.expectedL1ToL2Calls().isEmpty()) {
      violations.add("anchor must be call-free");
    }
    if (!anchor.success() || !anchor.returnData().isEmpty()) {
      violations.add("anchor must succeed with empty return data");
    }
  }
}
