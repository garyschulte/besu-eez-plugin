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
import org.hyperledger.besu.ethereum.mainnet.AbstractBlockProcessor;
import org.hyperledger.besu.ethereum.mainnet.BalConfiguration;
import org.hyperledger.besu.ethereum.mainnet.MainnetBlockProcessor;
import org.hyperledger.besu.ethereum.mainnet.MainnetTransactionProcessor;
import org.hyperledger.besu.ethereum.mainnet.MiningBeneficiaryCalculator;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.ValidationResult;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.AccessLocationTracker;
import org.hyperledger.besu.ethereum.mainnet.parallelization.ParallelBlockTransactionProcessor;
import org.hyperledger.besu.ethereum.mainnet.systemcall.BlockProcessingContext;
import org.hyperledger.besu.ethereum.processing.TransactionProcessingResult;
import org.hyperledger.besu.ethereum.transaction.TransactionInvalidReason;
import org.hyperledger.besu.evm.blockhash.BlockHashLookup;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

import java.util.Optional;

/**
 * Imports blocks under the Rollup0 draft's one block rule above transaction execution: derivation
 * only emits a 0x45 transaction for an action that succeeded on the settlement chain, so a failed
 * one makes its block invalid (draft F.3).
 */
class Rollup0BlockProcessor extends MainnetBlockProcessor {

  Rollup0BlockProcessor(
      final MainnetTransactionProcessor transactionProcessor,
      final AbstractBlockProcessor.TransactionReceiptFactory transactionReceiptFactory,
      final MiningBeneficiaryCalculator miningBeneficiaryCalculator,
      final ProtocolSchedule protocolSchedule,
      final BalConfiguration balConfiguration) {
    super(
        transactionProcessor,
        transactionReceiptFactory,
        miningBeneficiaryCalculator,
        protocolSchedule,
        balConfiguration);
  }

  @Override
  protected TransactionProcessingResult processTransaction(
      final Optional<ParallelBlockTransactionProcessor> parallelProcessor,
      final BlockProcessingContext blockProcessingContext,
      final WorldUpdater transactionUpdater,
      final Wei blobGasPrice,
      final Address miningBeneficiary,
      final Transaction transaction,
      final int location,
      final BlockHashLookup blockHashLookup,
      final Optional<AccessLocationTracker> accessLocationTracker) {
    final TransactionProcessingResult result =
        super.processTransaction(
            parallelProcessor,
            blockProcessingContext,
            transactionUpdater,
            blobGasPrice,
            miningBeneficiary,
            transaction,
            location,
            blockHashLookup,
            accessLocationTracker);
    if (transaction.getType().isProtocolDerived()
        && !result.isInvalid()
        && !result.isSuccessful()) {
      return TransactionProcessingResult.invalid(
          ValidationResult.invalid(
              TransactionInvalidReason.EXECUTION_HALTED,
              "protocol-derived transaction did not complete successfully"));
    }
    return result;
  }
}
