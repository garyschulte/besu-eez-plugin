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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import eez.besu.follower.chain.EezChainConfig;
import eez.besu.follower.engine.EngineDriver;
import eez.besu.follower.engine.EngineRpc;
import eez.besu.follower.l1.BeaconClient;
import eez.besu.follower.l1.L1Block;
import eez.besu.follower.l1.L1Client;
import eez.besu.follower.l1.L1Exception;
import eez.besu.follower.l1.L1Log;
import eez.besu.follower.protocol.codec.ActionBracket;
import eez.besu.follower.protocol.codec.EezMessage;
import eez.besu.follower.protocol.codec.EezStreamEncoder;
import eez.besu.follower.protocol.codec.NativeBlock;
import eez.besu.follower.protocol.codec.NativeBlockSpan;
import eez.besu.follower.protocol.codec.NativeBlockSpanV0Codec;
import eez.besu.follower.protocol.eez.EezBatch;
import eez.besu.follower.protocol.eez.EezEventDecoder;
import eez.besu.follower.protocol.eez.InboundAction;
import eez.besu.follower.protocol.eez.InboundTransactions;
import eez.besu.follower.protocol.eez.L1ExecutionEntry;
import eez.besu.follower.protocol.eez.PublicInputs;
import eez.besu.follower.protocol.eez.Rollup0Constants;
import eez.besu.follower.protocol.eez.Rollup0Deployment;
import eez.besu.follower.protocol.eez.RollupUpdate;
import eez.besu.follower.protocol.eez.SettlementCalldataDecoder;
import eez.besu.follower.protocol.eez.SettlementContext;
import eez.besu.follower.protocol.eez.abi.Abi;
import eez.besu.follower.protocol.eez.abi.AbiType;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.TransactionType;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.encoding.EncodingContext;
import org.hyperledger.besu.ethereum.core.encoding.TransactionDecoder;
import org.hyperledger.besu.evm.precompile.KZGPointEvalPrecompiledContract;
import org.hyperledger.besu.plugin.data.BlockHeader;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives the deriver against in-process fakes: a JSON-RPC settlement node, a beacon node serving
 * real blobs (authenticated by KZG), and an engine whose block hash is a digest of every block
 * input, so a committed root matches only if the deriver built the block exactly as draft 10.2
 * prescribes.
 */
class Rollup0SettlementDeriverTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final long GENESIS_TIME = 1_700_000_000L;
  private static final long BLOCK_INTERVAL = 2L;
  private static final int POSITIONS = 6;
  private static final long L1_SLOT = 12L;
  private static final long L1_HEAD = 20L;
  private static final long BEACON_GENESIS = GENESIS_TIME - 1_000 * L1_SLOT;

  private static final long RID = 7L;
  private static final BigInteger CHAIN_ID = BigInteger.valueOf(424242);
  private static final Address EEZ = address(0xee01);
  private static final Address MANAGER = address(0xe002);
  private static final Address WRAPPER = address(0xe003);
  private static final Address RELAYER = address(0xe004);
  private static final Address PROOF_SYSTEM = address(0xe005);
  private static final Address OTHER = address(0xe006);

  private static final EezChainConfig CONFIG =
      new EezChainConfig(
          CHAIN_ID,
          BigInteger.valueOf(RID),
          BigInteger.ONE,
          EEZ,
          MANAGER,
          WRAPPER,
          EezChainConfig.DEFAULT_SETTLEMENT_WRAPPER_FUNCTION,
          EezChainConfig.DEFAULT_EEZ_L2_ADDRESS,
          OTHER,
          0L,
          Optional.empty(),
          BLOCK_INTERVAL,
          POSITIONS,
          EezChainConfig.DEFAULT_BASE_FEE_MAX_CHANGE_DENOMINATOR);

  private static final Rollup0Deployment DEPLOYMENT =
      new Rollup0Deployment(
          BigInteger.ONE, EEZ, MANAGER, WRAPPER, RID, CHAIN_ID, CONFIG.eezL2Address());

  private static final Hash SAFE_HEAD = Hash.wrap(Bytes32.repeat((byte) 0xaa));
  private static final Hash SETTLEMENT_TX = Hash.wrap(Bytes32.repeat((byte) 0x5e));

  private static final Address BENEFICIARY_A = address(0xa1);
  private static final Address BENEFICIARY_B = address(0xb2);

  /** The fake engine's gas accounting: B[0] uses 1M, B[1] 20M, each later variant 1M more. */
  private static final long PREFIX_GAS = 1_000_000L;

  private static final long INBOUND_GAS = 19_000_000L;

  private HttpServer server;
  private final Map<Hash, FakeTransaction> transactions = new ConcurrentHashMap<>();
  private final Map<Long, List<Bytes>> blobsBySlot = new ConcurrentHashMap<>();
  private final List<Build> builds = new ArrayList<>();
  private final List<Hash> imports = new ArrayList<>();
  private final Map<Hash, Long> headerNumbers = new HashMap<>();
  private Rollup0SettlementDeriver deriver;

  /** A recorded {@code testing_buildBlockV1} call. */
  private record Build(
      Hash parent,
      long timestamp,
      Bytes32 prevRandao,
      Address beneficiary,
      Bytes extraData,
      List<String> transactions,
      Hash hash) {}

  /** A settlement-chain transaction as the fake JSON-RPC node returns it. */
  private record FakeTransaction(Map<String, Object> tx, String status) {}

  @BeforeAll
  static void loadKzg() {
    KZGPointEvalPrecompiledContract.init();
  }

  @BeforeEach
  void startFakes() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::jsonRpc);
    server.createContext("/eth/v1/beacon/", this::beacon);
    server.start();
    final URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    final L1Client l1 = new L1Client(endpoint);
    headerNumbers.put(SAFE_HEAD, 6L);
    deriver =
        new Rollup0SettlementDeriver(
            CONFIG,
            l1,
            new BeaconClient(endpoint),
            new EngineDriver(engine()),
            new RandaoSource(l1),
            new Rollup0Schedule(GENESIS_TIME, BLOCK_INTERVAL, POSITIONS),
            hash ->
                Optional.ofNullable(headerNumbers.get(hash))
                    .map(Rollup0SettlementDeriverTest::header));
  }

  @AfterEach
  void stopFakes() {
    server.stop(0);
  }

  // ---------------------------------------------------------------- successful derivations

  @Test
  void catchUpAnchorWithoutActionsRebuildsTheRange() {
    // terminal block 12 is at GENESIS + 24; settled one slot later, so a catch-up anchor
    final Settlement s = new Scenario().settledAt(GENESIS_TIME + 36).build();

    assertThat(deriver.derive(s.block(), s.logs(), SAFE_HEAD)).contains(s.roots().getFirst());

    assertThat(builds).hasSize(6);
    // P(T) for T = timestamp(6) = GENESIS + 12 is L1 block 1: block 2 sits exactly at T
    final Bytes32 seed = l1MixHash(1);
    Hash parent = SAFE_HEAD;
    for (int i = 0; i < 6; i++) {
      final Build build = builds.get(i);
      final NativeBlock expected = s.span().blocks().get(i);
      assertThat(build.parent()).isEqualTo(parent);
      assertThat(build.timestamp()).isEqualTo(GENESIS_TIME + 2L * (7 + i));
      assertThat(build.prevRandao()).isEqualTo(seed);
      assertThat(build.beneficiary()).isEqualTo(expected.beneficiary());
      assertThat(build.extraData()).isEqualTo(expected.extraData());
      assertThat(build.transactions())
          .containsExactlyElementsOf(
              expected.transactions().stream().map(Bytes::toHexString).toList());
      parent = build.hash();
    }
    // five non-terminal blocks and the terminal B[0], in order
    assertThat(imports).containsExactlyElementsOf(builds.stream().map(Build::hash).toList());
    assertThat(imports.getLast()).isEqualTo(s.roots().getFirst());
  }

  @Test
  void spanOverTwoIntervalsUsesEachIntervalsSeed() {
    final Scenario scenario = new Scenario();
    scenario.blocks = blocks(12);
    final Settlement s = scenario.settledAt(GENESIS_TIME + 48).build();

    assertThat(deriver.derive(s.block(), s.logs(), SAFE_HEAD)).contains(s.roots().getFirst());

    assertThat(builds).hasSize(12);
    // blocks 7-12 follow T = GENESIS + 12 (L1 block 1), blocks 13-18 T = GENESIS + 24 (block 2)
    builds.subList(0, 6).forEach(b -> assertThat(b.prevRandao()).isEqualTo(l1MixHash(1)));
    builds.subList(6, 12).forEach(b -> assertThat(b.prevRandao()).isEqualTo(l1MixHash(2)));
    assertThat(builds.getLast().timestamp()).isEqualTo(GENESIS_TIME + 36);
  }

  @Test
  void liveAnchorAppliesEveryConsumedAction() {
    final Scenario scenario = new Scenario().withActions(2);
    scenario.consumed = 2;
    final Settlement s = scenario.build();

    assertThat(deriver.derive(s.block(), s.logs(), SAFE_HEAD)).contains(s.roots().get(2));

    // five non-terminal blocks, then B[0], B[1], B[2] on the same parent with growing tx lists
    assertThat(builds).hasSize(8);
    final List<Build> variants = builds.subList(5, 8);
    final List<String> prefix =
        s.span().blocks().getLast().transactions().stream().map(Bytes::toHexString).toList();
    assertThat(variants).extracting(Build::parent).containsOnly(builds.get(4).hash());
    assertThat(variants).extracting(Build::timestamp).containsOnly(GENESIS_TIME + 24);
    assertThat(variants.get(0).transactions()).containsExactlyElementsOf(prefix);
    assertThat(variants.get(1).transactions()).startsWith(prefix.toArray(String[]::new));
    assertThat(variants.get(1).transactions()).hasSize(prefix.size() + 1);
    assertThat(variants.get(2).transactions())
        .startsWith(variants.get(1).transactions().toArray(String[]::new));
    assertThat(variants.get(2).transactions()).hasSize(prefix.size() + 2);
    assertThat(variants).extracting(Build::hash).containsExactlyElementsOf(s.roots());

    // gas limit min(30M - gasUsed(B[k-1]), 2^24): B[0] used 1M, B[1] used 20M
    final Transaction first = decode(variants.get(2).transactions().get(prefix.size()));
    final Transaction second = decode(variants.get(2).transactions().get(prefix.size() + 1));
    assertThat(first.getType()).isEqualTo(TransactionType.EEZ_INBOUND);
    assertThat(first.getGasLimit()).isEqualTo(Rollup0Constants.INBOUND_GAS_CAP);
    assertThat(second.getGasLimit()).isEqualTo(EezChainConfig.GAS_LIMIT - 20_000_000L);
    assertThat(first.getTo()).contains(CONFIG.eezL2Address());
    assertThat(
            InboundTransactions.decodeInput(second.getPayload()).entries().getFirst().returnData())
        .isEqualTo(s.actions().get(1).returnData());

    // only the terminal variant B[2] is imported, after the range's earlier blocks
    assertThat(imports)
        .containsExactlyElementsOf(
            List.of(
                builds.get(0).hash(),
                builds.get(1).hash(),
                builds.get(2).hash(),
                builds.get(3).hash(),
                builds.get(4).hash(),
                s.roots().get(2)));
  }

  @Test
  void stopsAtTheConsumedPrefix() {
    final Scenario scenario = new Scenario().withActions(2);
    scenario.consumed = 1;
    final Settlement s = scenario.build();

    assertThat(deriver.derive(s.block(), s.logs(), SAFE_HEAD)).contains(s.roots().get(1));

    assertThat(builds).hasSize(7);
    assertThat(imports).hasSize(6);
    assertThat(imports.getLast()).isEqualTo(s.roots().get(1));
  }

  @Test
  void blockWithoutRollupLogsDerivesNothing() {
    final Settlement s = new Scenario().withActions(1).build();
    final List<L1Log> foreign = new ArrayList<>();
    for (final L1Log log : s.logs()) {
      // the same events from another contract, and this contract's events for another rollup
      foreign.add(withAddress(log, OTHER));
    }
    foreign.add(rootUpdated(RID + 1, Bytes32.ZERO, Hash.ZERO, 9, 0));

    assertThat(deriver.derive(s.block(), foreign, SAFE_HEAD)).isEmpty();
    assertThat(deriver.derive(s.block(), List.of(), SAFE_HEAD)).isEmpty();
    assertThat(builds).isEmpty();
  }

  @Test
  void postedBatchWhoseAnchorDidNotApplyDerivesNothing() {
    final Settlement s = new Scenario().build();
    final List<L1Log> onlyPosted =
        s.logs().stream()
            .filter(l -> l.topics().getFirst().equals(EezEventDecoder.BATCH_POSTED.topic0()))
            .toList();
    assertThat(onlyPosted).hasSize(1);

    assertThat(deriver.derive(s.block(), onlyPosted, SAFE_HEAD)).isEmpty();
    assertThat(builds).isEmpty();
  }

  @Test
  void ignoresConsumptionsBeforeTheSettlement() {
    // the settlement is the block's second transaction; a pair of consumption logs precedes it
    final Scenario scenario = new Scenario().withActions(1);
    scenario.settlementIndex = 1;
    final Settlement s = scenario.build();
    final Hash earlier = Hash.wrap(Bytes32.repeat((byte) 0x60));
    final List<L1Log> logs = new ArrayList<>();
    logs.add(
        log(
            List.of(
                EezEventDecoder.EXECUTION_CONSUMED.topic0(),
                s.actions().getFirst().callHash(RID),
                word(BigInteger.valueOf(RID)),
                word(BigInteger.ZERO)),
            Bytes.EMPTY,
            earlier,
            0,
            0));
    logs.add(performed(Bytes32.wrap(s.roots().get(1).getBytes()), BigInteger.ONE, earlier, 0, 1));
    logs.addAll(s.logs());

    // as if no trigger ran: B[0] only
    assertThat(deriver.derive(s.block(), logs, SAFE_HEAD)).contains(s.roots().getFirst());
    assertThat(builds).hasSize(6);
    assertThat(imports.getLast()).isEqualTo(s.roots().getFirst());
  }

  @Test
  void retriesWhenTheSettlementTransactionIsInAnotherBlock() {
    final Scenario scenario = new Scenario();
    scenario.rpcBlockHash = Hash.wrap(Bytes32.repeat((byte) 0xbe));
    final Settlement s = scenario.build();

    assertThatThrownBy(() -> deriver.derive(s.block(), s.logs(), SAFE_HEAD))
        .isInstanceOf(L1Exception.class)
        .isNotInstanceOf(DerivationException.class);
    assertThat(builds).isEmpty();
  }

  @Test
  void retriesWhenTheSettlementTransactionIsAtAnotherPosition() {
    final Scenario scenario = new Scenario();
    scenario.rpcTransactionIndex = 3;
    final Settlement s = scenario.build();

    assertThatThrownBy(() -> deriver.derive(s.block(), s.logs(), SAFE_HEAD))
        .isInstanceOf(L1Exception.class)
        .isNotInstanceOf(DerivationException.class);
    assertThat(builds).isEmpty();
  }

  // ---------------------------------------------------------------- halting conditions

  @Test
  void haltsWhenTheAnchorDoesNotExtendTheCursor() {
    final Settlement s = new Scenario().build();
    final Hash elsewhere = Hash.wrap(Bytes32.repeat((byte) 0xab));
    headerNumbers.put(elsewhere, 6L);

    assertThatThrownBy(() -> deriver.derive(s.block(), s.logs(), elsewhere))
        .isInstanceOf(DerivationException.class)
        .hasMessageContaining("cursor");
    assertThat(builds).isEmpty();
  }

  @Test
  void haltsWhenTheBatchDoesNotMatchBatchPosted() {
    final Scenario scenario = new Scenario();
    scenario.sharedPublicInputOverride = Bytes32.repeat((byte) 0x11);
    final Settlement s = scenario.build();

    assertThatThrownBy(() -> deriver.derive(s.block(), s.logs(), SAFE_HEAD))
        .isInstanceOf(DerivationException.class)
        .hasMessageContaining("BatchPosted");
    assertThat(builds).isEmpty();
  }

  @Test
  void haltsWhenTheRebuiltAnchorDiffersFromTheCommittedRoot() {
    final Scenario scenario = new Scenario();
    scenario.rootOverrides.put(0, Bytes32.repeat((byte) 0x0b));
    final Settlement s = scenario.build();

    assertThatThrownBy(() -> deriver.derive(s.block(), s.logs(), SAFE_HEAD))
        .isInstanceOf(DerivationException.class)
        .hasMessageContaining("Rebuilt B[0]");
    // the terminal block is never imported
    assertThat(imports).hasSize(5);
  }

  @Test
  void haltsWhenARebuiltVariantDiffersFromItsCommittedRoot() {
    final Scenario scenario = new Scenario().withActions(2);
    scenario.consumed = 2;
    scenario.rootOverrides.put(2, Bytes32.repeat((byte) 0x0c));
    final Settlement s = scenario.build();

    assertThatThrownBy(() -> deriver.derive(s.block(), s.logs(), SAFE_HEAD))
        .isInstanceOf(DerivationException.class)
        .hasMessageContaining("Rebuilt B[2]");
    assertThat(imports).hasSize(5);
  }

  @Test
  void haltsWhenConsumptionsAreOutOfOrder() {
    final Scenario scenario = new Scenario().withActions(2);
    scenario.consumed = 2;
    scenario.cursor = k -> BigInteger.valueOf(k == 1 ? 1 : 0);
    final Settlement s = scenario.build();

    assertThatThrownBy(() -> deriver.derive(s.block(), s.logs(), SAFE_HEAD))
        .isInstanceOf(DerivationException.class)
        .hasMessageContaining("queue cursor");
    assertThat(builds).isEmpty();
  }

  @Test
  void haltsWhenAConsumptionHasAnotherCallHash() {
    final Scenario scenario = new Scenario().withActions(1);
    scenario.consumed = 1;
    scenario.consumedCallHash = Bytes32.repeat((byte) 0xca);
    final Settlement s = scenario.build();

    assertThatThrownBy(() -> deriver.derive(s.block(), s.logs(), SAFE_HEAD))
        .isInstanceOf(DerivationException.class)
        .hasMessageContaining("call hash");
    assertThat(builds).isEmpty();
  }

  @Test
  void haltsWhenACatchUpAnchorCarriesActions() {
    final Scenario scenario = new Scenario().withActions(1).settledAt(GENESIS_TIME + 36);
    scenario.consumed = 1;
    final Settlement s = scenario.build();

    assertThatThrownBy(() -> deriver.derive(s.block(), s.logs(), SAFE_HEAD))
        .isInstanceOf(DerivationException.class)
        .hasMessageContaining("Catch-up");
  }

  @Test
  void haltsWhenACatchUpAnchorCarriesAnUnconsumedManifest() {
    // a catch-up candidate has an empty action manifest (draft 5.x, 7.3), consumed or not
    final Settlement s = new Scenario().withActions(1).settledAt(GENESIS_TIME + 36).build();

    assertThatThrownBy(() -> deriver.derive(s.block(), s.logs(), SAFE_HEAD))
        .isInstanceOf(DerivationException.class)
        .hasMessageContaining("Catch-up");
    assertThat(imports).hasSize(5);
  }

  @Test
  void haltsWhenTheRangeEndsAfterItsSettlementBlock() {
    final Settlement s = new Scenario().settledAt(GENESIS_TIME + 12).build();

    assertThatThrownBy(() -> deriver.derive(s.block(), s.logs(), SAFE_HEAD))
        .isInstanceOf(DerivationException.class)
        .hasMessageContaining("after its settlement block");
  }

  @Test
  void haltsWhenTheTerminalBlockIsNotAtASyncPosition() {
    // a parent at block 5 puts the six-block range's terminal at block 11
    headerNumbers.put(SAFE_HEAD, 5L);
    final Scenario scenario = new Scenario();
    scenario.parentNumber = 5;
    final Settlement s = scenario.build();

    assertThatThrownBy(() -> deriver.derive(s.block(), s.logs(), SAFE_HEAD))
        .isInstanceOf(DerivationException.class)
        .hasMessageContaining("not a Sync block");
    assertThat(imports).hasSize(5);
  }

  @Test
  void haltsOnADirectRootUpdate() {
    final Settlement s = new Scenario().build();
    final List<L1Log> logs = new ArrayList<>(s.logs());
    logs.add(
        rootUpdated(
            RID, Bytes32.repeat((byte) 0x0e), Hash.wrap(Bytes32.repeat((byte) 0x77)), 5, 9));

    assertThatThrownBy(() -> deriver.derive(s.block(), logs, SAFE_HEAD))
        .isInstanceOf(DerivationException.class)
        .hasMessageContaining("set directly");
    assertThat(builds).isEmpty();
  }

  @Test
  void haltsWhenTheCommitmentMovesWithoutAnActivatedCandidate() {
    final Scenario scenario = new Scenario().withActions(1);
    scenario.consumed = 1;
    final Settlement s = scenario.build();
    // only the trigger's consumption and transition, with no settlement in the block
    final List<L1Log> triggerOnly =
        s.logs().stream().filter(l -> !l.transactionHash().equals(SETTLEMENT_TX)).toList();
    assertThat(triggerOnly).hasSize(2);

    assertThatThrownBy(() -> deriver.derive(s.block(), triggerOnly, SAFE_HEAD))
        .isInstanceOf(DerivationException.class)
        .hasMessageContaining("without an activated candidate");

    // nor with a posted batch whose anchor did not apply
    final List<L1Log> withPosted = new ArrayList<>(triggerOnly);
    withPosted.addFirst(
        s.logs().stream()
            .filter(l -> l.topics().getFirst().equals(EezEventDecoder.BATCH_POSTED.topic0()))
            .findFirst()
            .orElseThrow());
    assertThatThrownBy(() -> deriver.derive(s.block(), withPosted, SAFE_HEAD))
        .isInstanceOf(DerivationException.class)
        .hasMessageContaining("without an activated candidate");
    assertThat(builds).isEmpty();
  }

  // ---------------------------------------------------------------- scenario construction

  /** The inputs to one {@code derive} call and what the follower should arrive at. */
  private record Settlement(
      L1Block block,
      List<L1Log> logs,
      NativeBlockSpan span,
      List<InboundAction> actions,
      List<Hash> roots) {}

  /** Builds a settlement block for a range on {@link #SAFE_HEAD}, with optional tampering. */
  private final class Scenario {
    long parentNumber = 6;
    List<NativeBlock> blocks = blocks(6);
    List<ActionBracket> manifest = List.of();
    int consumed = 0;
    long settlementTimestamp = GENESIS_TIME + 24;
    final Map<Integer, Bytes32> rootOverrides = new HashMap<>();
    IntFunction<BigInteger> cursor = k -> BigInteger.valueOf(k - 1L);
    Bytes32 consumedCallHash;
    Bytes32 sharedPublicInputOverride;
    int settlementIndex = 0;
    Hash rpcBlockHash;
    Integer rpcTransactionIndex;

    Scenario withActions(final int count) {
      final List<ActionBracket> brackets = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        brackets.add(
            new ActionBracket(
                i,
                address(0xc0f10 + i),
                address(0xde100 + i),
                UInt256.valueOf(1_000L * (i + 1)),
                50_000L * i,
                Bytes.fromHexString("0xdeadbeef0" + i),
                true,
                Bytes.fromHexString("0xc0ffee0" + i)));
      }
      manifest = brackets;
      return this;
    }

    Scenario settledAt(final long timestamp) {
      settlementTimestamp = timestamp;
      return this;
    }

    Settlement build() {
      final long l1Number = (settlementTimestamp - l1Timestamp(0)) / L1_SLOT;
      final L1Block block =
          new L1Block(
              l1Number,
              l1Hash(l1Number),
              l1Hash(l1Number - 1),
              settlementTimestamp,
              l1MixHash(l1Number));
      final SettlementContext context =
          new SettlementContext(settlementTimestamp, Bytes32.wrap(block.parentHash().getBytes()));
      final NativeBlockSpan span = new NativeBlockSpan(blocks);
      final List<InboundAction> actions =
          manifest.stream()
              .map(
                  b ->
                      new InboundAction(
                          b.manifestIndex(),
                          b.fromAddress(),
                          b.toAddress(),
                          b.value().toBigInteger(),
                          b.gas(),
                          b.data(),
                          b.returnData()))
              .toList();

      // the roots the fake engine produces for a range built per draft 10.2
      Hash parent = SAFE_HEAD;
      long number = parentNumber;
      for (int i = 0; i < blocks.size() - 1; i++) {
        number++;
        parent = expectedHash(parent, number, blocks.get(i), blocks.get(i).transactions());
      }
      number++;
      final NativeBlock terminal = blocks.getLast();
      final List<Bytes> txs = new ArrayList<>(terminal.transactions());
      final List<Hash> roots = new ArrayList<>();
      roots.add(expectedHash(parent, number, terminal, txs));
      for (int k = 1; k <= actions.size(); k++) {
        final long remaining = EezChainConfig.GAS_LIMIT - gasUsed(k - 1);
        txs.add(
            InboundTransactions.envelope(
                InboundTransactions.derive(DEPLOYMENT, context, actions.get(k - 1), remaining)));
        roots.add(expectedHash(parent, number, terminal, txs));
      }
      final List<Bytes32> committed = new ArrayList<>();
      for (int k = 0; k < roots.size(); k++) {
        committed.add(rootOverrides.getOrDefault(k, Bytes32.wrap(roots.get(k).getBytes())));
      }

      // the batch: the anchor Hparent -> H[0] and one deferred entry per successful action
      final List<L1ExecutionEntry> entries = new ArrayList<>();
      entries.add(
          new L1ExecutionEntry(
              List.of(
                  new RollupUpdate(
                      RID,
                      BigInteger.ZERO,
                      Bytes32.wrap(SAFE_HEAD.getBytes()),
                      committed.getFirst())),
              Bytes32.ZERO,
              List.of(),
              List.of(),
              Bytes32.repeat((byte) 0x01),
              RID,
              true,
              Bytes.EMPTY));
      for (int k = 1; k <= actions.size(); k++) {
        final InboundAction action = actions.get(k - 1);
        entries.add(
            new L1ExecutionEntry(
                List.of(
                    new RollupUpdate(RID, action.value(), committed.get(k - 1), committed.get(k))),
                action.callHash(RID),
                List.of(),
                List.of(),
                Bytes32.repeat((byte) (0x10 + k)),
                RID,
                true,
                action.returnData()));
      }

      final List<EezMessage> messages = new ArrayList<>();
      messages.add(new EezMessage.ChainOperation(RID, NativeBlockSpanV0Codec.encode(span)));
      for (final ActionBracket b : manifest) {
        messages.add(new EezMessage.InitiateCrossChainTransaction(0, Bytes.EMPTY));
        messages.add(
            new EezMessage.Call(RID, b.fromAddress(), b.toAddress(), b.value(), b.gas(), b.data()));
        messages.add(new EezMessage.ReturnSuccess(b.returnData()));
        messages.add(new EezMessage.FinishCrossChainTransaction());
      }
      messages.add(new EezMessage.CloseBlobStream());
      final EezStreamEncoder.EncodedStream stream = EezStreamEncoder.encode(messages);
      assertThat(stream.callData()).isEqualTo(Bytes.EMPTY);
      final List<Bytes> blobs = stream.blobs();
      blobsBySlot.put((settlementTimestamp - BEACON_GENESIS) / L1_SLOT, blobs);
      final List<Bytes32> versionedHashes =
          blobs.stream().map(b -> Bytes32.wrap(BeaconClient.versionedHash(b).getBytes())).toList();

      final EezBatch batch =
          new EezBatch(
              List.of(),
              entries,
              List.of(),
              BigInteger.ONE,
              BigInteger.ZERO,
              List.of(PROOF_SYSTEM),
              List.of(new EezBatch.RollupProofSystems(RID, List.of(0L))),
              IntStream.range(0, blobs.size()).mapToObj(BigInteger::valueOf).toList(),
              Bytes.EMPTY,
              List.of(Bytes.of(1)),
              Rollup0Constants.LATEST_CONTEXT_BLOCK_NUMBER,
              true);
      final Bytes32 sharedPublicInput =
          sharedPublicInputOverride != null
              ? sharedPublicInputOverride
              : PublicInputs.sharedPublicInput(
                  batch,
                  versionedHashes,
                  List.of(PublicInputs.candidateCustomData(DEPLOYMENT, context)),
                  WRAPPER);

      final Map<String, Object> tx = new LinkedHashMap<>();
      tx.put("blockHash", (rpcBlockHash != null ? rpcBlockHash : block.hash()).toHexString());
      tx.put(
          "transactionIndex",
          "0x"
              + Integer.toHexString(
                  rpcTransactionIndex != null ? rpcTransactionIndex : settlementIndex));
      tx.put("from", RELAYER.toHexString());
      tx.put("to", WRAPPER.toHexString());
      tx.put(
          "input",
          SettlementCalldataDecoder.batchFunction(CONFIG.settlementWrapperFunction())
              .encodeCall(List.of(batch.toAbi()))
              .toHexString());
      tx.put("blobVersionedHashes", versionedHashes.stream().map(Bytes::toHexString).toList());
      transactions.put(SETTLEMENT_TX, new FakeTransaction(tx, "0x1"));

      // settlement: L2ExecutionPerformed(H[0]) then BatchPosted; each trigger: consumed, performed
      final List<L1Log> logs = new ArrayList<>();
      logs.add(performed(committed.getFirst(), BigInteger.ZERO, SETTLEMENT_TX, settlementIndex, 0));
      logs.add(
          log(
              List.of(EezEventDecoder.BATCH_POSTED.topic0()),
              Abi.encode(
                  AbiType.parseTuple("(bytes32,uint64[])"),
                  List.of(sharedPublicInput, List.of(BigInteger.valueOf(RID)))),
              SETTLEMENT_TX,
              settlementIndex,
              1));
      for (int k = 1; k <= consumed; k++) {
        final Hash trigger = Hash.wrap(Bytes32.repeat((byte) (0x70 + k)));
        final int index = 2 * k;
        logs.add(
            log(
                List.of(
                    EezEventDecoder.EXECUTION_CONSUMED.topic0(),
                    consumedCallHash != null ? consumedCallHash : actions.get(k - 1).callHash(RID),
                    word(BigInteger.valueOf(RID)),
                    word(cursor.apply(k))),
                Bytes.EMPTY,
                trigger,
                settlementIndex + k,
                index));
        logs.add(
            performed(
                committed.get(k),
                actions.get(k - 1).value(),
                trigger,
                settlementIndex + k,
                index + 1));
      }
      return new Settlement(block, logs, span, actions, roots);
    }

    private Hash expectedHash(
        final Hash parent, final long number, final NativeBlock block, final List<Bytes> txs) {
      final long timestamp = GENESIS_TIME + BLOCK_INTERVAL * number;
      // P(T) for the interval's opening Sync timestamp T, found independently of RandaoSource
      final long t = GENESIS_TIME + BLOCK_INTERVAL * (((number - 1) / POSITIONS) * POSITIONS);
      long p = 0;
      while (p + 1 <= L1_HEAD && l1Timestamp(p + 1) < t) {
        p++;
      }
      return blockHash(
          parent.toHexString(),
          "0x" + Long.toHexString(timestamp),
          l1MixHash(p).toHexString(),
          block.beneficiary().toHexString(),
          block.extraData().toHexString(),
          txs.stream().map(Bytes::toHexString).toList());
    }
  }

  private static List<NativeBlock> blocks(final int count) {
    final List<NativeBlock> blocks = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      final List<Bytes> txs = new ArrayList<>();
      for (int t = 0; t < i % 3; t++) {
        txs.add(Bytes.fromHexString(String.format("0x02%02x%02x", i, t)));
      }
      blocks.add(
          new NativeBlock(
              i < 3 ? BENEFICIARY_A : BENEFICIARY_B,
              i % 2 == 0 ? Bytes.EMPTY : Bytes.fromHexString(String.format("0x0102%02x", i)),
              txs));
    }
    // the terminal block always has a pure-L2 prefix
    final NativeBlock last = blocks.getLast();
    blocks.set(
        count - 1,
        new NativeBlock(
            last.beneficiary(), last.extraData(), List.of(Bytes.fromHexString("0x02ffee"))));
    return blocks;
  }

  // ---------------------------------------------------------------- the fake engine

  private EngineRpc engine() {
    return (method, params) -> {
      switch (method) {
        case "testing_buildBlockV1" -> {
          @SuppressWarnings("unchecked")
          final Map<String, Object> attributes = (Map<String, Object>) params[1];
          @SuppressWarnings("unchecked")
          final List<String> txs = (List<String>) params[2];
          final String timestamp = (String) attributes.get("timestamp");
          final Hash hash =
              blockHash(
                  (String) params[0],
                  timestamp,
                  (String) attributes.get("prevRandao"),
                  (String) attributes.get("suggestedFeeRecipient"),
                  (String) params[3],
                  txs);
          final long ts = Long.decode(timestamp);
          builds.add(
              new Build(
                  Hash.fromHexString((String) params[0]),
                  ts,
                  Bytes32.fromHexString((String) attributes.get("prevRandao")),
                  Address.fromHexString((String) attributes.get("suggestedFeeRecipient")),
                  Bytes.fromHexString((String) params[3]),
                  List.copyOf(txs),
                  hash));
          final long inbound = txs.stream().filter(t -> t.startsWith("0x45")).count();
          final Map<String, Object> payload = new LinkedHashMap<>();
          payload.put("blockHash", hash.toHexString());
          payload.put("blockNumber", "0x" + Long.toHexString((ts - GENESIS_TIME) / BLOCK_INTERVAL));
          payload.put("gasUsed", "0x" + Long.toHexString(gasUsed(inbound)));
          return MAPPER.valueToTree(Map.of("executionPayload", payload));
        }
        case "engine_newPayloadV4" -> {
          final Map<?, ?> payload = (Map<?, ?>) params[0];
          imports.add(Hash.fromHexString((String) payload.get("blockHash")));
          return MAPPER.valueToTree(Map.of("status", "VALID"));
        }
        default -> throw new IllegalArgumentException("Unexpected engine call " + method);
      }
    };
  }

  private static long gasUsed(final long inboundCount) {
    return inboundCount == 0 ? PREFIX_GAS : INBOUND_GAS + PREFIX_GAS * inboundCount;
  }

  /** The fake engine's block hash: a digest of every input the deriver chooses. */
  private static Hash blockHash(
      final String parent,
      final String timestamp,
      final String prevRandao,
      final String beneficiary,
      final String extraData,
      final List<String> transactions) {
    final String digest =
        String.join(
            "|",
            parent,
            timestamp,
            prevRandao.toLowerCase(Locale.ROOT),
            beneficiary.toLowerCase(Locale.ROOT),
            extraData,
            String.join(",", transactions));
    return Hash.hash(Bytes.wrap(digest.getBytes(StandardCharsets.UTF_8)));
  }

  private static BlockHeader header(final long number) {
    return (BlockHeader)
        Proxy.newProxyInstance(
            BlockHeader.class.getClassLoader(),
            new Class<?>[] {BlockHeader.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "getNumber" -> number;
                  case "toString" -> "header " + number;
                  case "hashCode" -> Long.hashCode(number);
                  case "equals" -> proxy == args[0];
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  // ---------------------------------------------------------------- the fake settlement chain

  /** Settlement blocks every slot from {@code GENESIS - 12}, so block 1 is at the genesis time. */
  private static long l1Timestamp(final long number) {
    return GENESIS_TIME + L1_SLOT * (number - 1);
  }

  private static Address address(final long value) {
    return Address.wrap(Bytes.fromHexString(String.format("0x%040x", value)));
  }

  private static Hash l1Hash(final long number) {
    return Hash.wrap(word(BigInteger.valueOf(0x1000 + number)));
  }

  private static Bytes32 l1MixHash(final long number) {
    return word(BigInteger.valueOf(0x5000 + number));
  }

  private static Map<String, Object> l1Block(final long number) {
    return Map.of(
        "number", "0x" + Long.toHexString(number),
        "hash", l1Hash(number).toHexString(),
        "parentHash", l1Hash(number - 1).toHexString(),
        "timestamp", "0x" + Long.toHexString(l1Timestamp(number)),
        "mixHash", l1MixHash(number).toHexString());
  }

  private void jsonRpc(final HttpExchange exchange) throws IOException {
    final JsonNode request = MAPPER.readTree(exchange.getRequestBody());
    final JsonNode params = request.get("params");
    final Object result =
        switch (request.get("method").asText()) {
          case "eth_getBlockByNumber" -> {
            final String tag = params.get(0).asText();
            final long number = "latest".equals(tag) ? L1_HEAD : Long.decode(tag);
            yield number <= L1_HEAD ? l1Block(number) : null;
          }
          case "eth_getTransactionByHash" -> {
            final FakeTransaction tx = transactions.get(Hash.fromHexString(params.get(0).asText()));
            yield tx == null ? null : tx.tx();
          }
          case "eth_getTransactionReceipt" -> {
            final FakeTransaction tx = transactions.get(Hash.fromHexString(params.get(0).asText()));
            yield tx == null ? null : Map.of("status", tx.status());
          }
          default -> throw new IllegalArgumentException("Unexpected L1 call " + request);
        };
    final Map<String, Object> response = new HashMap<>();
    response.put("jsonrpc", "2.0");
    response.put("id", request.get("id").asLong());
    response.put("result", result);
    respond(exchange, 200, MAPPER.writeValueAsBytes(response));
  }

  private void beacon(final HttpExchange exchange) throws IOException {
    final String path = exchange.getRequestURI().getPath();
    if (path.equals("/eth/v1/beacon/genesis")) {
      respond(
          exchange,
          200,
          MAPPER.writeValueAsBytes(
              Map.of("data", Map.of("genesis_time", Long.toString(BEACON_GENESIS)))));
      return;
    }
    final String prefix = "/eth/v1/beacon/blobs/";
    final List<Bytes> blobs =
        path.startsWith(prefix)
            ? blobsBySlot.get(Long.parseLong(path.substring(prefix.length())))
            : null;
    if (blobs == null) {
      respond(exchange, 404, new byte[0]);
      return;
    }
    respond(
        exchange,
        200,
        MAPPER.writeValueAsBytes(Map.of("data", blobs.stream().map(Bytes::toHexString).toList())));
  }

  private static void respond(final HttpExchange exchange, final int status, final byte[] body)
      throws IOException {
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
    try (var out = exchange.getResponseBody()) {
      out.write(body);
    }
  }

  // ---------------------------------------------------------------- logs

  private static L1Log log(
      final List<Bytes32> topics,
      final Bytes data,
      final Hash transaction,
      final int transactionIndex,
      final int logIndex) {
    return new L1Log(EEZ, topics, data, 0L, Hash.ZERO, transaction, transactionIndex, logIndex);
  }

  private static L1Log performed(
      final Bytes32 newRoot,
      final BigInteger balance,
      final Hash transaction,
      final int transactionIndex,
      final int logIndex) {
    return log(
        List.of(EezEventDecoder.L2_EXECUTION_PERFORMED.topic0(), word(BigInteger.valueOf(RID))),
        Bytes.concatenate(newRoot, word(balance)),
        transaction,
        transactionIndex,
        logIndex);
  }

  private static L1Log rootUpdated(
      final long rollupId,
      final Bytes32 newRoot,
      final Hash transaction,
      final int transactionIndex,
      final int logIndex) {
    return log(
        List.of(EezEventDecoder.ROOT_UPDATED.topic0(), word(BigInteger.valueOf(rollupId))),
        newRoot,
        transaction,
        transactionIndex,
        logIndex);
  }

  private static L1Log withAddress(final L1Log log, final Address address) {
    return new L1Log(
        address,
        log.topics(),
        log.data(),
        log.blockNumber(),
        log.blockHash(),
        log.transactionHash(),
        log.transactionIndex(),
        log.logIndex());
  }

  private static Bytes32 word(final BigInteger value) {
    return UInt256.valueOf(value).toBytes();
  }

  private static Transaction decode(final String envelope) {
    return TransactionDecoder.decodeOpaqueBytes(
        Bytes.fromHexString(envelope), EncodingContext.BLOCK_BODY);
  }
}
