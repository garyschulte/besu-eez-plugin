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
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

import java.util.List;

/**
 * rollupX burns whatever ETH is left on its protocol accounts, {@code EEZL2} and the system sender,
 * after each complete cross-chain group and again when the block closes (eez-evm balances.rs).
 */
final class ProtocolBalances {

  static final List<Address> ACCOUNTS =
      List.of(RollupXChainConfig.EEZ_L2_ADDRESS, Transaction.PROTOCOL_DERIVED_SENDER);

  private ProtocolBalances() {}

  /**
   * Zeroes the balance of each protocol account. Only the balance changes; an account left empty is
   * removed, as the EVM removes a touched empty account.
   */
  static void clear(final WorldUpdater updater) {
    for (final Address address : ACCOUNTS) {
      final MutableAccount account = updater.getAccount(address);
      if (account == null || account.getBalance().isZero()) {
        continue;
      }
      account.setBalance(Wei.ZERO);
      if (account.isEmpty()) {
        updater.deleteAccount(address);
      }
    }
  }
}
