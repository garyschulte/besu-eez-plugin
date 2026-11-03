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
package eez.besu.follower.derive;

import eez.besu.follower.l1.L1Block;
import eez.besu.follower.l1.L1Client;
import eez.besu.follower.l1.L1Exception;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.tuweni.bytes.Bytes32;

/**
 * Finds {@code P(T)}, the canonical settlement block with the greatest timestamp strictly less than
 * {@code T}, whose {@code prevRandao} seeds the Rollup0 interval after Sync timestamp {@code T}
 * (draft 4.2, 10.2).
 */
public class RandaoSource {

  private final L1Client l1;
  private final Map<Long, L1Block> blocks = new ConcurrentHashMap<>();
  private final Map<Long, Bytes32> seeds = new ConcurrentHashMap<>();

  public RandaoSource(final L1Client l1) {
    this.l1 = l1;
  }

  /**
   * Returns {@code P(T).prevRandao}.
   *
   * @param syncTimestamp the Sync timestamp {@code T}
   * @return the seed
   * @throws L1Exception if the settlement chain has not yet produced a block at or after {@code T},
   *     since {@code P(T)} is not settled until then
   */
  public Bytes32 seedFor(final long syncTimestamp) {
    return seeds.computeIfAbsent(syncTimestamp, t -> precedingBlock(t).prevRandao());
  }

  /** Forgets cached blocks and seeds, after a settlement-chain reorganization. */
  public void invalidate() {
    blocks.clear();
    seeds.clear();
  }

  private L1Block precedingBlock(final long syncTimestamp) {
    final L1Block head = l1.block("latest");
    if (head.timestamp() < syncTimestamp) {
      throw new L1Exception("Settlement chain has not reached timestamp " + syncTimestamp);
    }
    // greatest block number whose timestamp is strictly less than T; timestamps strictly increase
    long low = 0;
    long high = head.number();
    while (low < high) {
      final long mid = low + (high - low + 1) / 2;
      if (block(mid).timestamp() < syncTimestamp) {
        low = mid;
      } else {
        high = mid - 1;
      }
    }
    final L1Block found = block(low);
    if (found.timestamp() >= syncTimestamp) {
      throw new L1Exception("No settlement block precedes timestamp " + syncTimestamp);
    }
    return found;
  }

  private L1Block block(final long number) {
    return blocks.computeIfAbsent(
        number,
        n -> l1.block(n).orElseThrow(() -> new L1Exception("Settlement block " + n + " missing")));
  }
}
