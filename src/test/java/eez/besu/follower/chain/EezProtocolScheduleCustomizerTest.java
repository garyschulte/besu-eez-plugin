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

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.config.GenesisConfig;
import org.hyperledger.besu.config.GenesisConfigOptions;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.TransactionType;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.chain.BadBlockManager;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.BlockHeaderTestFixture;
import org.hyperledger.besu.ethereum.core.MiningConfiguration;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.mainnet.BalConfiguration;
import org.hyperledger.besu.ethereum.mainnet.MainnetProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.ProtocolScheduleCustomization;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpec;
import org.hyperledger.besu.ethereum.mainnet.TransactionValidationParams;
import org.hyperledger.besu.ethereum.mainnet.feemarket.BaseFeeMarket;
import org.hyperledger.besu.ethereum.transaction.TransactionInvalidReason;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;

class EezProtocolScheduleCustomizerTest {

  private static final Address FEE_COLLECTOR =
      Address.fromHexString("0xfeec011ec7000000000000000000000000000000");

  private final AtomicReference<EezChainConfig> resolved = new AtomicReference<>();
  private final AtomicReference<RollupXChainConfig> resolvedRollupX = new AtomicReference<>();
  private final EezProtocolScheduleCustomizer customizer =
      new EezProtocolScheduleCustomizer(resolved::set, resolvedRollupX::set);

  @Test
  void readsTheEezGenesisSection() throws IOException {
    final GenesisConfigOptions genesis = devGenesis();

    assertThat(customizer.customize(genesis)).isPresent();

    final EezChainConfig config = resolved.get();
    assertThat(config.chainId()).isEqualTo(BigInteger.valueOf(17733));
    assertThat(config.rollupId()).isEqualTo(BigInteger.ONE);
    assertThat(config.feeCollector()).isEqualTo(FEE_COLLECTOR);
    assertThat(config.eezL2Address()).isEqualTo(EezChainConfig.DEFAULT_EEZ_L2_ADDRESS);
    assertThat(config.baseFeeMaxChangeDenominator()).isEqualTo(50L);
    assertThat(config.positionsPerInterval()).isEqualTo(6);
  }

  @Test
  void doesNotApplyWithoutAnEezSection() {
    final GenesisConfigOptions genesis =
        GenesisConfig.fromConfig("{\"config\":{\"chainId\":1,\"londonBlock\":0}}")
            .getConfigOptions();

    assertThat(customizer.customize(genesis)).isEmpty();
    assertThat(resolved.get()).isNull();
    assertThat(resolvedRollupX.get()).isNull();
  }

  @Test
  void readsTheLiveRollupXGenesis() throws IOException {
    final GenesisConfigOptions genesis = genesis("/rollupx-chiado-genesis.json");

    final ProtocolScheduleCustomization customization = customizer.customize(genesis).orElseThrow();

    assertThat(customization.name()).isEqualTo(EezProtocolScheduleCustomizer.ROLLUPX_NAME);
    assertThat(resolved.get()).isNull();
    final RollupXChainConfig config = resolvedRollupX.get();
    assertThat(config.chainId()).isEqualTo(BigInteger.valueOf(699169));
    assertThat(config.feeCollector())
        .isEqualTo(Address.fromHexString("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee0078"));
    assertThat(config.blockIntervalSeconds()).isEqualTo(1L);
    assertThat(config.syncIntervalBlocks()).isEqualTo(5);
    assertThat(config.historyUpdateOffset()).isEqualTo(2);
    assertThat(config.l1Reference().chainId()).isEqualTo(BigInteger.valueOf(10200));
    assertThat(config.l1Reference().number()).isEqualTo(23731489L);
  }

  @Test
  void rollupXSpecAcceptsHistoryTransactionsAndSweepsProtocolBalances() throws IOException {
    final GenesisConfigOptions genesis = genesis("/rollupx-chiado-genesis.json");
    final ProtocolSpec spec = specAt(genesis, 0x6ae1f994L);

    assertThat(EezProtocolScheduleCustomizer.ROLLUPX_ACCEPTED_TRANSACTION_TYPES)
        .contains(TransactionType.EEZ_HISTORY)
        .doesNotContain(TransactionType.BLOB);
    assertThat(spec.getBlockProcessor()).isInstanceOf(RollupXBlockProcessor.class);
    assertThat(spec.getBlockRewardProcessor()).isInstanceOf(RollupXBlockRewardProcessor.class);
    assertThat(((BaseFeeMarket) spec.getFeeMarket()).getBaseFeeCollector())
        .contains(Address.fromHexString("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee0078"));
  }

  @Test
  void rollup0SpecRejectsFailedProtocolTransactions() throws IOException {
    assertThat(specAt(12).getBlockProcessor()).isInstanceOf(Rollup0BlockProcessor.class);
  }

  @Test
  void feeMarketMovesByOneFiftiethAndCollectsTheBaseFee() throws IOException {
    final ProtocolSpec spec = specAt(12);

    assertThat(spec.getFeeMarket()).isInstanceOf(EezFeeMarket.class);
    final BaseFeeMarket feeMarket = (BaseFeeMarket) spec.getFeeMarket();
    assertThat(feeMarket.getBaseFeeCollector()).contains(FEE_COLLECTOR);
    // a full block (twice the 15M target) raises the base fee by 1/50
    assertThat(feeMarket.computeBaseFee(2, Wei.of(1_000_000_000L), 30_000_000L, 15_000_000L))
        .isEqualTo(Wei.of(1_020_000_000L));
    // an empty block lowers it by 1/50
    assertThat(feeMarket.computeBaseFee(2, Wei.of(1_000_000_000L), 0L, 15_000_000L))
        .isEqualTo(Wei.of(980_000_000L));
  }

  @Test
  void writesBlockHistoryButNoBeaconRootsAndNoRequests() throws IOException {
    final ProtocolSpec spec = specAt(12);

    assertThat(spec.getPreExecutionProcessor()).isInstanceOf(EezPreExecutionProcessor.class);
    assertThat(spec.getPreExecutionProcessor().getBeaconRootsContract()).isEmpty();
    assertThat(spec.getPreExecutionProcessor().getHistoryContract())
        .contains(EezPreExecutionProcessor.HISTORY_STORAGE_ADDRESS);
    assertThat(spec.getRequestProcessorCoordinator().orElseThrow().getContractConfigs()).isEmpty();
  }

  @Test
  void acceptsInboundTransactionsAndRejectsBlobTransactions() throws IOException {
    final ProtocolSpec spec = specAt(12);
    final Transaction inbound =
        Transaction.builder()
            .type(TransactionType.EEZ_INBOUND)
            .chainId(BigInteger.valueOf(17733))
            .sourceHash(Bytes32.repeat((byte) 0x11))
            .gasLimit(100_000L)
            .to(EezChainConfig.DEFAULT_EEZ_L2_ADDRESS)
            .value(Wei.ZERO)
            .payload(Bytes.EMPTY)
            .build();

    assertThat(
            spec.getTransactionValidatorFactory()
                .get()
                .validate(
                    inbound,
                    Optional.of(Wei.of(1_000_000_000L)),
                    Optional.of(Wei.ONE),
                    TransactionValidationParams.processingBlock())
                .isValid())
        .isTrue();
    assertThat(EezProtocolScheduleCustomizer.ACCEPTED_TRANSACTION_TYPES)
        .doesNotContain(TransactionType.BLOB);
  }

  @Test
  void wrongChainInboundTransactionIsInvalid() throws IOException {
    final Transaction otherChain =
        Transaction.builder()
            .type(TransactionType.EEZ_INBOUND)
            .chainId(BigInteger.ONE)
            .sourceHash(Bytes32.repeat((byte) 0x11))
            .gasLimit(100_000L)
            .to(EezChainConfig.DEFAULT_EEZ_L2_ADDRESS)
            .value(Wei.ZERO)
            .payload(Bytes.EMPTY)
            .build();

    assertThat(
            specAt(12)
                .getTransactionValidatorFactory()
                .get()
                .validate(
                    otherChain,
                    Optional.of(Wei.of(1_000_000_000L)),
                    Optional.of(Wei.ONE),
                    TransactionValidationParams.processingBlock())
                .getInvalidReason())
        .isEqualTo(TransactionInvalidReason.WRONG_CHAIN_ID);
  }

  private ProtocolSpec specAt(final long timestamp) throws IOException {
    return specAt(devGenesis(), timestamp);
  }

  private ProtocolSpec specAt(final GenesisConfigOptions genesis, final long timestamp) {
    final ProtocolScheduleCustomization customization = customizer.customize(genesis).orElseThrow();
    final ProtocolSchedule schedule =
        MainnetProtocolSchedule.fromConfig(
            genesis,
            Optional.empty(),
            Optional.of(EvmConfiguration.DEFAULT),
            MiningConfiguration.MINING_DISABLED,
            new BadBlockManager(),
            false,
            BalConfiguration.DEFAULT,
            new NoOpMetricsSystem(),
            customization);
    final BlockHeader header =
        new BlockHeaderTestFixture().number(1).timestamp(timestamp).buildHeader();
    return schedule.getByBlockHeader(header);
  }

  private static GenesisConfigOptions devGenesis() throws IOException {
    return genesis("/eez-dev-genesis.json");
  }

  private static GenesisConfigOptions genesis(final String resource) throws IOException {
    try (var in = EezProtocolScheduleCustomizerTest.class.getResourceAsStream(resource)) {
      return GenesisConfig.fromConfig(new String(in.readAllBytes(), StandardCharsets.UTF_8))
          .getConfigOptions();
    }
  }
}
