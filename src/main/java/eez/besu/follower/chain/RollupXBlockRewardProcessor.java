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
package eez.besu.follower.chain;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.ProcessableBlockHeader;
import org.hyperledger.besu.ethereum.mainnet.BlockRewardProcessor;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.hyperledger.besu.plugin.services.worldstate.MutableWorldState;

import java.util.List;

/**
 * Pays no block reward but, as the step that closes every block in both import and block building,
 * burns the protocol accounts' residual ETH once the block's transactions have run.
 */
final class RollupXBlockRewardProcessor implements BlockRewardProcessor {

  @Override
  public boolean rewardBeneficiaries(
      final MutableWorldState worldState,
      final ProcessableBlockHeader header,
      final List<BlockHeader> ommers,
      final Address miningBeneficiary) {
    final WorldUpdater updater = worldState.updater();
    ProtocolBalances.clear(updater);
    updater.commit();
    return true;
  }

  @Override
  public Wei getBlockReward() {
    return Wei.ZERO;
  }

  @Override
  public Wei getCoinbaseReward(final int numberOfOmmers) {
    return Wei.ZERO;
  }

  @Override
  public Wei getOmmerReward(final long blockNumber, final long ommerBlockNumber) {
    return Wei.ZERO;
  }
}
