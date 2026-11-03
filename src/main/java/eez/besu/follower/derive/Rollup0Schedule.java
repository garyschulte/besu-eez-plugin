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

import eez.besu.follower.chain.EezChainConfig;

/**
 * The Rollup0 block schedule (draft 4.1): block {@code n} has timestamp {@code genesis + n *
 * interval}, and every {@code positions}-th block, starting with genesis, is a Sync block.
 */
public class Rollup0Schedule {

  private final long genesisTimestamp;
  private final long blockInterval;
  private final int positions;

  public Rollup0Schedule(final long genesisTimestamp, final EezChainConfig config) {
    this(genesisTimestamp, config.blockInterval(), config.positionsPerInterval());
  }

  public Rollup0Schedule(
      final long genesisTimestamp, final long blockInterval, final int positions) {
    this.genesisTimestamp = genesisTimestamp;
    this.blockInterval = blockInterval;
    this.positions = positions;
  }

  /** Returns the scheduled timestamp of block {@code number}. */
  public long timestamp(final long number) {
    return Math.addExact(genesisTimestamp, Math.multiplyExact(number, blockInterval));
  }

  /** Whether block {@code number} is at a Sync position. */
  public boolean isSync(final long number) {
    return number % positions == 0;
  }

  /**
   * Returns the timestamp {@code T} of the Sync block that opens the interval containing block
   * {@code number}: the interval {@code (T, T + positions * interval]} whose blocks all copy {@code
   * P(T).prevRandao} (draft 4.2). Genesis belongs to the interval its own timestamp opens.
   *
   * @param number a block number
   * @return the opening Sync timestamp
   */
  public long intervalOpeningSyncTimestamp(final long number) {
    if (number == 0) {
      return genesisTimestamp;
    }
    return timestamp(((number - 1) / positions) * positions);
  }

  /** The number of blocks between consecutive Sync blocks. */
  public int positions() {
    return positions;
  }
}
