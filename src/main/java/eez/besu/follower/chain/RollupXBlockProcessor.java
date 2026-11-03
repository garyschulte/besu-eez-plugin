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
import org.hyperledger.besu.datatypes.TransactionType;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.BlockProcessingResult;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.mainnet.AbstractBlockProcessor;
import org.hyperledger.besu.ethereum.mainnet.BalConfiguration;
import org.hyperledger.besu.ethereum.mainnet.BlockExecutionContext;
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

import java.util.List;
import java.util.Optional;

/**
 * Imports blocks under the rollupX block rules (eez-evm block.rs and eez-protocol system_tx.rs)
 * that sit above single-transaction execution:
 *
 * <ul>
 *   <li>a 0x46 history transaction may only open the block, and must succeed without output or
 *       logs;
 *   <li>a 0x45 transaction calls {@code EEZL2} with {@code loadExecutionTable} or {@code
 *       executeIncomingCrossChainCall}, and its gas limit is exactly the block's remaining gas,
 *       capped at 2^24;
 *   <li>a table load is immediately followed by the ordinary transaction it serves, so a load
 *       cannot end the block or precede another protocol transaction;
 *   <li>each completed group, an incoming call or a load with its transaction, burns the protocol
 *       accounts' residual ETH; {@link RollupXBlockRewardProcessor} does the same when the block
 *       closes.
 * </ul>
 *
 * Not enforced here: that a history import and the block's {@code prevRandao} match the settlement
 * chain, which needs settlement headers rather than L2 state.
 */
class RollupXBlockProcessor extends MainnetBlockProcessor {

  private final ThreadLocal<BlockInProgress> current = new ThreadLocal<>();

  private static final class BlockInProgress {
    private final List<Transaction> transactions;
    private long cumulativeGasUsed;

    private BlockInProgress(final List<Transaction> transactions) {
      this.transactions = transactions;
    }
  }

  RollupXBlockProcessor(
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
  public BlockProcessingResult processBlock(final BlockExecutionContext context) {
    final List<Transaction> transactions = context.getBlock().getBody().getTransactions();
    final Optional<String> violation = structuralViolation(transactions);
    if (violation.isPresent()) {
      return new BlockProcessingResult(Optional.empty(), violation.get());
    }
    current.set(new BlockInProgress(transactions));
    try {
      return super.processBlock(context);
    } finally {
      current.remove();
    }
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
    final BlockInProgress block = current.get();
    if (block == null) {
      throw new IllegalStateException("rollupX transactions are processed only within a block");
    }
    if (transaction.getType() == TransactionType.EEZ_INBOUND) {
      final long remaining =
          blockProcessingContext.getBlockHeader().getGasLimit() - block.cumulativeGasUsed;
      final long expected = Math.min(remaining, RollupXChainConfig.SYSTEM_TX_GAS_CAP);
      if (transaction.getGasLimit() != expected) {
        return invalid(
            "0x45 gas limit "
                + transaction.getGasLimit()
                + " differs from its block position's "
                + expected);
      }
    }

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
    if (result.isInvalid()) {
      return result;
    }
    if (transaction.getType() == TransactionType.EEZ_HISTORY
        && (!result.isSuccessful()
            || !result.getOutput().isEmpty()
            || !result.getLogs().isEmpty())) {
      return invalid("0x46 history transaction failed");
    }
    block.cumulativeGasUsed += transaction.getGasLimit() - result.getGasRemaining();
    if (completesGroup(block.transactions, location)) {
      ProtocolBalances.clear(transactionUpdater);
    }
    return result;
  }

  /** The block's shape against the position and grouping rules, checked before execution. */
  static Optional<String> structuralViolation(final List<Transaction> transactions) {
    boolean awaitingUser = false;
    for (int i = 0; i < transactions.size(); i++) {
      final Transaction tx = transactions.get(i);
      final TransactionType type = tx.getType();
      if (type.isProtocolDerived() && awaitingUser) {
        return Optional.of("a table load must be followed by its user transaction, not " + type);
      }
      if (type == TransactionType.EEZ_HISTORY && i != 0) {
        return Optional.of("a 0x46 history transaction may only open the block");
      }
      if (type == TransactionType.EEZ_INBOUND) {
        if (!tx.getTo().map(RollupXChainConfig.EEZ_L2_ADDRESS::equals).orElse(false)) {
          return Optional.of("a 0x45 transaction must call EEZL2");
        }
        if (isLoad(tx)) {
          awaitingUser = true;
        } else if (!isIncomingCall(tx)) {
          return Optional.of("a 0x45 transaction calls an unsupported EEZL2 function");
        }
      } else if (!type.isProtocolDerived()) {
        awaitingUser = false;
      }
    }
    return awaitingUser
        ? Optional.of("the block ends inside a table load and its user transaction")
        : Optional.empty();
  }

  /**
   * Whether the transaction at {@code location} closes a cross-chain group: an incoming call, or
   * the ordinary transaction right after a table load.
   */
  static boolean completesGroup(final List<Transaction> transactions, final int location) {
    final Transaction tx = transactions.get(location);
    if (tx.getType() == TransactionType.EEZ_INBOUND) {
      return isIncomingCall(tx);
    }
    return !tx.getType().isProtocolDerived()
        && location > 0
        && transactions.get(location - 1).getType() == TransactionType.EEZ_INBOUND
        && isLoad(transactions.get(location - 1));
  }

  private static boolean isLoad(final Transaction tx) {
    return tx.getPayload().size() >= 4
        && tx.getPayload().slice(0, 4).equals(RollupXChainConfig.LOAD_EXECUTION_TABLE_SELECTOR);
  }

  private static boolean isIncomingCall(final Transaction tx) {
    return tx.getPayload().size() >= 4
        && tx.getPayload().slice(0, 4).equals(RollupXChainConfig.EXECUTE_INCOMING_SELECTOR);
  }

  private static TransactionProcessingResult invalid(final String message) {
    return TransactionProcessingResult.invalid(
        ValidationResult.invalid(TransactionInvalidReason.EXECUTION_HALTED, message));
  }
}
