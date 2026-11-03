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
import eez.besu.follower.l1.L1Log;

import org.hyperledger.besu.datatypes.Hash;

import java.util.List;
import java.util.Optional;

/**
 * Derives and imports the Rollup0 range one settlement block selects (draft 10.2). A candidate is
 * activated by its settlement transaction and then advanced by trigger transactions later in the
 * same block (draft 7.4), so a whole block's EEZ logs are processed together.
 */
public interface SettlementDeriver {

  /**
   * Processes the EEZ contract's logs in one settlement block.
   *
   * @param block the settlement block
   * @param blockLogs the block's EEZ logs, in log order
   * @param safeHead the Rollup0 block the settlement cursor currently points at
   * @return the new safe head if this block advanced the Rollup0 cursor, otherwise empty
   * @throws DerivationException if canonical data cannot be reconciled with the rules; the follower
   *     halts
   */
  Optional<Hash> derive(L1Block block, List<L1Log> blockLogs, Hash safeHead);
}
