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
package eez.besu.follower;

import eez.besu.follower.chain.EezChainConfig;
import eez.besu.follower.chain.EezProtocolScheduleCustomizer;
import eez.besu.follower.chain.RollupXChainConfig;
import eez.besu.follower.derive.Follower;
import eez.besu.follower.derive.FollowerState;
import eez.besu.follower.derive.RandaoSource;
import eez.besu.follower.derive.Rollup0Schedule;
import eez.besu.follower.derive.Rollup0SettlementDeriver;
import eez.besu.follower.derive.SettlementDeriver;
import eez.besu.follower.engine.EngineDriver;
import eez.besu.follower.engine.InProcessEngineRpc;
import eez.besu.follower.l1.BeaconClient;
import eez.besu.follower.l1.L1Client;

import org.hyperledger.besu.ethereum.mainnet.ProtocolScheduleService;
import org.hyperledger.besu.plugin.BesuPlugin;
import org.hyperledger.besu.plugin.ServiceManager;
import org.hyperledger.besu.plugin.data.BlockContext;
import org.hyperledger.besu.plugin.data.BlockHeader;
import org.hyperledger.besu.plugin.services.BesuConfiguration;
import org.hyperledger.besu.plugin.services.BesuService;
import org.hyperledger.besu.plugin.services.BlockchainService;
import org.hyperledger.besu.plugin.services.PicoCLIOptions;
import org.hyperledger.besu.plugin.services.RpcEndpointService;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.google.auto.service.AutoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns Besu into an EEZ Rollup0 follower.
 *
 * <p>During registration the plugin contributes the Rollup0 execution rules through the
 * protocol-schedule customizer; they apply only to a genesis with an {@code eez} section. When the
 * node starts on such a chain, the follower derives Rollup0 blocks from the settlement chain and
 * drives this node through its own Engine API.
 */
@AutoService(BesuPlugin.class)
public class EezFollowerPlugin implements BesuPlugin {

  private static final Logger LOG = LoggerFactory.getLogger(EezFollowerPlugin.class);

  private final EezOptions options = new EezOptions();
  private final AtomicReference<EezChainConfig> chainConfig = new AtomicReference<>();
  private final AtomicReference<RollupXChainConfig> rollupXConfig = new AtomicReference<>();
  private ServiceManager services;
  private Optional<Follower> follower = Optional.empty();

  @Override
  public void register(final ServiceManager context) {
    this.services = context;
    context
        .getService(PicoCLIOptions.class)
        .ifPresent(cli -> cli.addPicoCLIOptions("eez", options));
    context
        .getService(ProtocolScheduleService.class)
        .orElseThrow(() -> new IllegalStateException("ProtocolScheduleService is not available"))
        .registerProtocolScheduleCustomizer(
            new EezProtocolScheduleCustomizer(chainConfig::set, rollupXConfig::set));
  }

  @Override
  public void start() {
    final RollupXChainConfig rollupX = rollupXConfig.get();
    if (rollupX != null) {
      // rollupX derivation is not implemented: blocks reach this node through its Engine API
      LOG.info(
          "rollupX execution rules active for chain {}; settlement derivation is not available,"
              + " import blocks through the Engine API",
          rollupX.chainId());
      return;
    }
    final EezChainConfig config = chainConfig.get();
    if (config == null) {
      LOG.info("The genesis has no eez or rollupX section; the EEZ follower is inactive");
      return;
    }
    LOG.info("Rollup0 execution rules active for chain {}", config.chainId());
    if (!options.followerEnabled) {
      return;
    }
    if (options.l1RpcUrl == null || options.l1BeaconUrl == null) {
      throw new IllegalStateException(
          "The EEZ follower needs --plugin-eez-l1-rpc-url and --plugin-eez-l1-beacon-url");
    }
    follower = Optional.of(createFollower(config));
    follower.get().start();
  }

  private Follower createFollower(final EezChainConfig config) {
    final RpcEndpointService rpc = service(RpcEndpointService.class);
    final BlockchainService blockchain = service(BlockchainService.class);
    final BesuConfiguration besuConfiguration = service(BesuConfiguration.class);
    final L1Client l1 = new L1Client(options.l1RpcUrl);
    final BeaconClient beacon = new BeaconClient(options.l1BeaconUrl);
    final EngineDriver engine = new EngineDriver(new InProcessEngineRpc(rpc));
    final RandaoSource randao = new RandaoSource(l1);
    final BlockHeader genesis =
        blockchain
            .getBlockByNumber(0)
            .map(BlockContext::getBlockHeader)
            .orElseThrow(() -> new IllegalStateException("No genesis block"));
    final SettlementDeriver deriver =
        new Rollup0SettlementDeriver(
            config,
            l1,
            beacon,
            engine,
            randao,
            new Rollup0Schedule(genesis.getTimestamp(), config),
            blockchain::getBlockHeaderByHash);
    return new Follower(
        l1,
        config.settlementEezAddress(),
        deriver,
        engine,
        randao,
        besuConfiguration.getDataPath().resolve("eez-follower-state.json"),
        options.pollInterval(),
        FollowerState.initial(genesis.getBlockHash(), config.settlementStartBlock()));
  }

  private <T extends BesuService> T service(final Class<T> type) {
    return services
        .getService(type)
        .orElseThrow(() -> new IllegalStateException(type.getSimpleName() + " is not available"));
  }

  @Override
  public void stop() {
    follower.ifPresent(Follower::stop);
  }
}
