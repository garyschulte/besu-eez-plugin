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

import static org.hyperledger.besu.ethereum.mainnet.ProtocolScheduleActivation.timestamp;

import org.hyperledger.besu.config.GenesisConfigOptions;
import org.hyperledger.besu.datatypes.BlobType;
import org.hyperledger.besu.datatypes.TransactionType;
import org.hyperledger.besu.ethereum.mainnet.ProtocolScheduleCustomization;
import org.hyperledger.besu.ethereum.mainnet.ProtocolScheduleCustomizer;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpecBuilder;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpecModification;
import org.hyperledger.besu.ethereum.mainnet.TransactionValidatorFactory;
import org.hyperledger.besu.ethereum.mainnet.requests.RequestProcessorCoordinator;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Applies EEZ execution rules over Osaka from genesis, in one of two profiles chosen by the genesis
 * config section:
 *
 * <ul>
 *   <li>{@code eez}: the Rollup0 draft (chapters 3, 4 and 11);
 *   <li>{@code rollupX}: the profile of the reference client and the live network, which adds the
 *       0x46 L1 history transaction and cross-chain group rules.
 * </ul>
 *
 * Both share the core of the draft:
 *
 * <ul>
 *   <li>the EIP-1559 base fee moves by at most 1/50 per block and is credited to a fee collector
 *       rather than burned;
 *   <li>the type-0x45 inbound transaction is valid and blob transactions are not;
 *   <li>before its transactions a block makes the EIP-2935 history write but no EIP-4788 call;
 *   <li>blocks carry no EIP-7685 requests.
 * </ul>
 *
 * Every other rule is Osaka's. The genesis must activate Osaka at timestamp 0.
 */
public class EezProtocolScheduleCustomizer implements ProtocolScheduleCustomizer {

  static final String NAME = "eez-rollup0";
  static final String ROLLUPX_NAME = "eez-rollupx";

  static final Set<TransactionType> ACCEPTED_TRANSACTION_TYPES =
      Set.of(
          TransactionType.FRONTIER,
          TransactionType.ACCESS_LIST,
          TransactionType.EIP1559,
          TransactionType.DELEGATE_CODE,
          TransactionType.EEZ_INBOUND);

  static final Set<TransactionType> ROLLUPX_ACCEPTED_TRANSACTION_TYPES =
      Set.of(
          TransactionType.FRONTIER,
          TransactionType.ACCESS_LIST,
          TransactionType.EIP1559,
          TransactionType.DELEGATE_CODE,
          TransactionType.EEZ_INBOUND,
          TransactionType.EEZ_HISTORY);

  private final Consumer<EezChainConfig> onRollup0;
  private final Consumer<RollupXChainConfig> onRollupX;

  /**
   * @param onRollup0 receives the configuration when the genesis is a Rollup0 draft chain
   * @param onRollupX receives the configuration when the genesis is a rollupX chain
   */
  public EezProtocolScheduleCustomizer(
      final Consumer<EezChainConfig> onRollup0, final Consumer<RollupXChainConfig> onRollupX) {
    this.onRollup0 = onRollup0;
    this.onRollupX = onRollupX;
  }

  @Override
  public Optional<ProtocolScheduleCustomization> customize(final GenesisConfigOptions genesis) {
    final Optional<EezChainConfig> rollup0 = EezChainConfig.fromGenesis(genesis);
    final Optional<RollupXChainConfig> rollupX = RollupXChainConfig.fromGenesis(genesis);
    if (rollup0.isPresent() && rollupX.isPresent()) {
      throw new IllegalArgumentException(
          "A genesis may carry an eez or a rollupX section, not both");
    }
    if (rollupX.isPresent()) {
      onRollupX.accept(rollupX.get());
      return Optional.of(
          new ProtocolScheduleCustomization(
              ROLLUPX_NAME,
              List.of(
                  new ProtocolSpecModification(
                      timestamp(0L), builder -> rollupX(builder, genesis, rollupX.get())))));
    }
    return rollup0.map(
        config -> {
          onRollup0.accept(config);
          return new ProtocolScheduleCustomization(
              NAME,
              List.of(
                  new ProtocolSpecModification(
                      timestamp(0L), builder -> rollup0(builder, genesis, config))));
        });
  }

  static ProtocolSpecBuilder rollupX(
      final ProtocolSpecBuilder builder,
      final GenesisConfigOptions genesis,
      final RollupXChainConfig config) {
    return builder
        .feeMarketBuilder(
            blobSchedule ->
                new EezFeeMarket(
                    genesis.getBaseFeePerGas(),
                    blobSchedule.getBaseFeeUpdateFraction(),
                    RollupXChainConfig.BASE_FEE_MAX_CHANGE_DENOMINATOR,
                    config.feeCollector()))
        .transactionValidatorFactoryBuilder(
            (evm, gasLimitCalculator, feeMarket) ->
                new TransactionValidatorFactory(
                    evm.getGasCalculator(),
                    gasLimitCalculator,
                    feeMarket,
                    true,
                    Optional.of(config.chainId()),
                    ROLLUPX_ACCEPTED_TRANSACTION_TYPES,
                    Set.of(BlobType.KZG_CELL_PROOFS),
                    evm.getMaxInitcodeSize()))
        .preExecutionProcessor(new EezPreExecutionProcessor())
        .requestProcessorCoordinator(RequestProcessorCoordinator.noOp())
        .blockProcessorBuilder(RollupXBlockProcessor::new)
        .blockRewardProcessor(new RollupXBlockRewardProcessor());
  }

  static ProtocolSpecBuilder rollup0(
      final ProtocolSpecBuilder builder,
      final GenesisConfigOptions genesis,
      final EezChainConfig config) {
    return builder
        .feeMarketBuilder(
            blobSchedule ->
                new EezFeeMarket(
                    genesis.getBaseFeePerGas(),
                    blobSchedule.getBaseFeeUpdateFraction(),
                    config.baseFeeMaxChangeDenominator(),
                    config.feeCollector()))
        .transactionValidatorFactoryBuilder(
            (evm, gasLimitCalculator, feeMarket) ->
                new TransactionValidatorFactory(
                    evm.getGasCalculator(),
                    gasLimitCalculator,
                    feeMarket,
                    true,
                    Optional.of(config.chainId()),
                    ACCEPTED_TRANSACTION_TYPES,
                    Set.of(BlobType.KZG_CELL_PROOFS),
                    evm.getMaxInitcodeSize()))
        .preExecutionProcessor(new EezPreExecutionProcessor())
        .requestProcessorCoordinator(RequestProcessorCoordinator.noOp())
        .blockProcessorBuilder(Rollup0BlockProcessor::new);
  }
}
