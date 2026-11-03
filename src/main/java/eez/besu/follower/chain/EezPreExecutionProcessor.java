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
import org.hyperledger.besu.ethereum.mainnet.block.access.list.AccessLocationTracker;
import org.hyperledger.besu.ethereum.mainnet.blockhash.FrontierPreExecutionProcessor;
import org.hyperledger.besu.ethereum.mainnet.systemcall.BlockProcessingContext;
import org.hyperledger.besu.ethereum.mainnet.systemcall.SystemCallProcessor;

import java.util.Optional;

/**
 * Rollup0 keeps the EIP-2935 parent-hash write before a block's transactions, but has no beacon
 * chain and so makes no EIP-4788 beacon-roots call (draft 4.2).
 */
public class EezPreExecutionProcessor extends FrontierPreExecutionProcessor {

  /** EIP-2935 history storage contract. */
  public static final Address HISTORY_STORAGE_ADDRESS =
      Address.fromHexString("0x0000f90827f1c53a10cb7a02335b175320002935");

  @Override
  public Void process(
      final BlockProcessingContext context,
      final Optional<AccessLocationTracker> accessLocationTracker) {
    final SystemCallProcessor processor =
        new SystemCallProcessor(context.getProtocolSpec().getTransactionProcessor());
    // as on Ethereum, a missing or failing history storage contract does not invalidate the block
    processor.processUnchecked(
        HISTORY_STORAGE_ADDRESS,
        context,
        context.getBlockHeader().getParentHash().getBytes(),
        accessLocationTracker);
    return null;
  }

  @Override
  public Optional<Address> getHistoryContract() {
    return Optional.of(HISTORY_STORAGE_ADDRESS);
  }
}
