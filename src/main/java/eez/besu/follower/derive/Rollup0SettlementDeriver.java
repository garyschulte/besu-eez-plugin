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

import eez.besu.follower.chain.EezChainConfig;
import eez.besu.follower.engine.BlockEnvironment;
import eez.besu.follower.engine.BuiltBlock;
import eez.besu.follower.engine.EngineDriver;
import eez.besu.follower.engine.EngineRpcException;
import eez.besu.follower.l1.BeaconClient;
import eez.besu.follower.l1.L1Block;
import eez.besu.follower.l1.L1Client;
import eez.besu.follower.l1.L1Exception;
import eez.besu.follower.l1.L1Log;
import eez.besu.follower.l1.L1Transaction;
import eez.besu.follower.protocol.codec.ActionBracket;
import eez.besu.follower.protocol.codec.NativeBlock;
import eez.besu.follower.protocol.codec.NativeBlockSpan;
import eez.besu.follower.protocol.codec.NativeBlockSpanV0Codec;
import eez.besu.follower.protocol.codec.PayloadDecodingException;
import eez.besu.follower.protocol.codec.Rollup0Candidate;
import eez.besu.follower.protocol.codec.Rollup0Profile;
import eez.besu.follower.protocol.eez.EezBatch;
import eez.besu.follower.protocol.eez.EezEvent;
import eez.besu.follower.protocol.eez.EezEventDecoder;
import eez.besu.follower.protocol.eez.InboundAction;
import eez.besu.follower.protocol.eez.InboundTransactions;
import eez.besu.follower.protocol.eez.L1ExecutionEntry;
import eez.besu.follower.protocol.eez.PublicInputs;
import eez.besu.follower.protocol.eez.Rollup0BatchProfile;
import eez.besu.follower.protocol.eez.Rollup0Deployment;
import eez.besu.follower.protocol.eez.RollupUpdate;
import eez.besu.follower.protocol.eez.SettlementCalldataDecoder;
import eez.besu.follower.protocol.eez.SettlementContext;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.VersionedHash;
import org.hyperledger.besu.plugin.data.BlockHeader;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Derives the Rollup0 range a settlement block selects, following draft 10.2.
 *
 * <p>The settlement transaction posts the EEZ batch and moves the Rollup0 commitment from {@code
 * Hparent} to {@code H[0]}; triggers later in the block each consume the next successful action and
 * move it to {@code H[k]}. The batch carries every one of those commitments, signed; the logs say
 * how many actually applied. The follower decodes the batch from calldata and the blocks and action
 * manifest from the batch's blobs, rebuilds every block in the range with Besu, and requires each
 * rebuilt commitment to equal the settled one. Only the terminal variant {@code B[k]} is imported,
 * along with the range's earlier blocks.
 */
public class Rollup0SettlementDeriver implements SettlementDeriver {

  private static final Logger LOG = LoggerFactory.getLogger(Rollup0SettlementDeriver.class);

  private final EezChainConfig config;
  private final Rollup0Deployment deployment;
  private final L1Client l1;
  private final BeaconClient beacon;
  private final EngineDriver engine;
  private final RandaoSource randao;
  private final Rollup0Schedule schedule;
  private final Function<Hash, Optional<BlockHeader>> headers;
  private final EezEventDecoder events;
  private final SettlementCalldataDecoder settlementCalls;
  private final long rollupId;

  public Rollup0SettlementDeriver(
      final EezChainConfig config,
      final L1Client l1,
      final BeaconClient beacon,
      final EngineDriver engine,
      final RandaoSource randao,
      final Rollup0Schedule schedule,
      final Function<Hash, Optional<BlockHeader>> headers) {
    this.config = config;
    this.l1 = l1;
    this.beacon = beacon;
    this.engine = engine;
    this.randao = randao;
    this.schedule = schedule;
    this.headers = headers;
    this.rollupId = config.rollupId().longValueExact();
    this.deployment =
        new Rollup0Deployment(
            config.settlementChainId(),
            config.settlementEezAddress(),
            config.managerAddress(),
            config.settlementWrapperAddress(),
            rollupId,
            config.chainId(),
            config.eezL2Address());
    this.events = new EezEventDecoder(config.settlementEezAddress());
    this.settlementCalls =
        new SettlementCalldataDecoder(
            config.settlementEezAddress(),
            config.settlementWrapperAddress(),
            config.settlementWrapperFunction());
  }

  /** A decoded log of this rollup, with its position. */
  private record Event(L1Log log, EezEvent event) {}

  /** A trigger that consumed the next successful action: cursor {@code k - 1}, new root H[k]. */
  private record Consumption(BigInteger cursor, Bytes32 callHash, Bytes32 newRoot) {}

  @Override
  public Optional<Hash> derive(
      final L1Block block, final List<L1Log> blockLogs, final Hash safeHead) {
    final List<Event> rollupEvents = new ArrayList<>();
    for (final L1Log log : blockLogs) {
      events
          .decode(log.address(), log.topics(), log.data())
          .filter(this::concernsRollup)
          .ifPresent(event -> rollupEvents.add(new Event(log, event)));
    }
    if (rollupEvents.isEmpty()) {
      return Optional.empty();
    }
    // the escape path changes the commitment outside derivation (draft 10.6); never follow it
    for (final Event e : rollupEvents) {
      if (e.event() instanceof EezEvent.RootUpdated) {
        throw new DerivationException(
            "Rollup0 root was set directly in settlement block " + block.number());
      }
    }

    // the settlement transaction both posts the batch and applies the anchor, in one receipt
    final Optional<Event> anchor = activatedAnchor(rollupEvents);
    if (anchor.isEmpty()) {
      if (rollupEvents.stream().anyMatch(e -> !(e.event() instanceof EezEvent.BatchPosted))) {
        throw new DerivationException(
            "Rollup0 commitment moved in settlement block "
                + block.number()
                + " without an activated candidate");
      }
      // a posted batch whose anchor did not apply, such as a stale sibling, has no effect
      return Optional.empty();
    }
    final Hash settlementTxHash = anchor.get().log().transactionHash();
    final L1Transaction settlementTx = l1.transaction(settlementTxHash);
    // 10.2 step 1: the transaction must be the one at the log's position in this block; a
    // difference means the settlement chain changed under the read, so retry rather than halt
    if (!settlementTx.blockHash().equals(block.hash())
        || settlementTx.transactionIndex() != anchor.get().log().transactionIndex()) {
      throw new L1Exception(
          "Settlement transaction " + settlementTxHash + " is not at its logged position");
    }
    if (!settlementTx.successful()) {
      throw new DerivationException("Settlement transaction " + settlementTxHash + " failed");
    }

    // 10.2 steps 1-2: decode and profile-check the batch
    final SettlementCalldataDecoder.SettlementCall call =
        settlementCalls
            .decode(settlementTx.from(), settlementTx.to().orElseThrow(), settlementTx.input())
            .orElseThrow(
                () ->
                    new DerivationException(
                        "Cannot decode the EEZ batch from settlement transaction "
                            + settlementTxHash));
    final EezBatch batch = call.batch();
    final List<String> violations =
        Rollup0BatchProfile.violations(batch, rollupId, settlementTx.blobVersionedHashes().size());
    if (!violations.isEmpty()) {
      throw new DerivationException("Settlement batch violates the Rollup0 profile: " + violations);
    }

    // 10.2 step 5: the batch and its context are what EEZ verified
    final SettlementContext context =
        new SettlementContext(block.timestamp(), Bytes32.wrap(block.parentHash().getBytes()));
    final List<Bytes32> blobHashes =
        batch.selectBlobVersionedHashes(
            settlementTx.blobVersionedHashes().stream()
                .map(vh -> Bytes32.wrap(vh.getBytes()))
                .toList());
    final Bytes32 sharedPublicInput =
        PublicInputs.sharedPublicInput(
            batch,
            blobHashes,
            List.of(PublicInputs.candidateCustomData(deployment, context)),
            call.eezCaller());
    final boolean posted =
        rollupEvents.stream()
            .anyMatch(
                e ->
                    e.log().transactionHash().equals(settlementTxHash)
                        && e.event() instanceof EezEvent.BatchPosted p
                        && p.sharedPublicInput().equals(sharedPublicInput));
    if (!posted) {
      throw new DerivationException(
          "Decoded batch does not match the BatchPosted public input in " + settlementTxHash);
    }

    // 10.2 step 7: the anchor extends the current cursor
    final RollupUpdate anchorUpdate = Rollup0BatchProfile.anchorUpdate(batch);
    if (!anchorUpdate.currentRoot().equals(safeHead.getBytes())) {
      throw new DerivationException(
          "Settled anchor extends "
              + anchorUpdate.currentRoot()
              + " but the Rollup0 cursor is "
              + safeHead);
    }
    final Bytes32 h0 = ((EezEvent.L2ExecutionPerformed) anchor.get().event()).newRoot();
    if (!h0.equals(anchorUpdate.newRoot())) {
      throw new DerivationException("Applied anchor root differs from the batch's H[0]");
    }

    // 10.2 steps 3-4, 6: authenticated blobs to blocks and the action manifest
    final List<Bytes> blobs =
        beacon.blobs(block.timestamp(), blobHashes.stream().map(VersionedHash::new).toList());
    final Rollup0Candidate candidate;
    final NativeBlockSpan span;
    try {
      candidate = Rollup0Profile.decode(blobs, batch.callData(), rollupId);
      span = NativeBlockSpanV0Codec.decode(candidate.operations(), config.positionsPerInterval());
    } catch (final PayloadDecodingException e) {
      throw new DerivationException("Invalid Rollup0 payload: " + e.getMessage(), e);
    }

    // 10.2 step 9: the successful actions, in order
    final List<Consumption> consumptions = consumptions(rollupEvents, anchor.get().log());
    final List<InboundAction> actions =
        successfulActions(candidate.manifest(), consumptions, batch);

    // 10.2 steps 7-8, 10-12: rebuild the range and compare every commitment
    final Hash newSafe =
        rebuild(
            block, context, safeHead, span, h0, candidate.manifest().size(), actions, consumptions);
    LOG.info(
        "Derived {} Rollup0 blocks with {} inbound actions from settlement block {}",
        span.blockCount(),
        actions.size(),
        block.number());
    return Optional.of(newSafe);
  }

  private boolean concernsRollup(final EezEvent event) {
    return switch (event) {
      case EezEvent.BatchPosted p -> p.rollupIds().contains(rollupId);
      case EezEvent.ExecutionConsumed c -> c.rollupId() == rollupId;
      case EezEvent.L2ExecutionPerformed p -> p.rollupId() == rollupId;
      case EezEvent.RootUpdated r -> r.rollupId() == rollupId;
      default -> false;
    };
  }

  /**
   * The anchor is the commitment update in the same transaction as a BatchPosted, with no
   * consumption before it.
   */
  private static Optional<Event> activatedAnchor(final List<Event> events) {
    for (final Event posted : events) {
      if (!(posted.event() instanceof EezEvent.BatchPosted)) {
        continue;
      }
      final Hash tx = posted.log().transactionHash();
      final List<Event> inTx =
          events.stream().filter(e -> e.log().transactionHash().equals(tx)).toList();
      if (inTx.stream().anyMatch(e -> e.event() instanceof EezEvent.ExecutionConsumed)) {
        continue;
      }
      final List<Event> anchors =
          inTx.stream().filter(e -> e.event() instanceof EezEvent.L2ExecutionPerformed).toList();
      if (anchors.size() == 1) {
        return Optional.of(anchors.getFirst());
      }
      if (anchors.size() > 1) {
        throw new DerivationException("Settlement transaction " + tx + " moved Rollup0 twice");
      }
    }
    return Optional.empty();
  }

  /**
   * Each trigger in a later transaction than the settlement: an ExecutionConsumed then its
   * L2ExecutionPerformed.
   */
  private static List<Consumption> consumptions(final List<Event> events, final L1Log anchorLog) {
    final List<Consumption> result = new ArrayList<>();
    EezEvent.ExecutionConsumed pending = null;
    for (final Event e : events) {
      // only triggers after the candidate's activation can consume its actions (10.2 step 9)
      if (e.log().transactionIndex() <= anchorLog.transactionIndex()) {
        continue;
      }
      switch (e.event()) {
        case EezEvent.ExecutionConsumed c -> {
          if (pending != null) {
            throw new DerivationException("Consumption without a Rollup0 transition");
          }
          pending = c;
        }
        case EezEvent.L2ExecutionPerformed p -> {
          if (pending == null) {
            throw new DerivationException("Rollup0 transition without a consumption");
          }
          result.add(
              new Consumption(
                  pending.entryQueueIndex(), pending.crossChainCallHash(), p.newRoot()));
          pending = null;
        }
        default -> {}
      }
    }
    if (pending != null) {
      throw new DerivationException("Consumption without a Rollup0 transition");
    }
    return result;
  }

  /**
   * Matches each consumption {@code k} to manifest bracket {@code k - 1} and the batch's deferred
   * entry {@code k - 1}, which must agree on call, outcome and transition.
   */
  private List<InboundAction> successfulActions(
      final List<ActionBracket> manifest,
      final List<Consumption> consumptions,
      final EezBatch batch) {
    final List<L1ExecutionEntry> deferred = Rollup0BatchProfile.deferredEntries(batch);
    final List<InboundAction> actions = new ArrayList<>();
    for (int i = 0; i < consumptions.size(); i++) {
      final Consumption consumption = consumptions.get(i);
      if (!consumption.cursor().equals(BigInteger.valueOf(i))) {
        throw new DerivationException(
            "Consumption " + (i + 1) + " has queue cursor " + consumption.cursor());
      }
      if (i >= manifest.size() || i >= deferred.size()) {
        throw new DerivationException("Consumption " + (i + 1) + " has no manifest action");
      }
      final ActionBracket bracket = manifest.get(i);
      if (!bracket.success()) {
        throw new DerivationException("Manifest action " + i + " consumed but marked failed");
      }
      final InboundAction action =
          new InboundAction(
              bracket.manifestIndex(),
              bracket.fromAddress(),
              bracket.toAddress(),
              bracket.value().toBigInteger(),
              bracket.gas(),
              bracket.data(),
              bracket.returnData());
      final Bytes32 callHash = action.callHash(rollupId);
      final L1ExecutionEntry entry = deferred.get(i);
      if (!callHash.equals(consumption.callHash()) || !callHash.equals(entry.proxyEntryHash())) {
        throw new DerivationException("Consumption " + (i + 1) + " does not match its call hash");
      }
      if (!entry.success() || !entry.returnData().equals(bracket.returnData())) {
        throw new DerivationException("Deferred entry " + i + " disagrees with the manifest");
      }
      if (entry.rollupUpdates().size() != 1
          || !entry.rollupUpdates().getFirst().newRoot().equals(consumption.newRoot())) {
        throw new DerivationException("Consumption " + (i + 1) + " applied an unexpected root");
      }
      actions.add(action);
    }
    return actions;
  }

  private Hash rebuild(
      final L1Block settlementBlock,
      final SettlementContext context,
      final Hash safeHead,
      final NativeBlockSpan span,
      final Bytes32 h0,
      final int manifestSize,
      final List<InboundAction> actions,
      final List<Consumption> consumptions) {
    final BlockHeader parentHeader =
        headers
            .apply(safeHead)
            .orElseThrow(() -> new DerivationException("Rollup0 cursor " + safeHead + " unknown"));
    Hash parent = safeHead;
    long number = parentHeader.getNumber();
    final List<NativeBlock> blocks = span.blocks();
    for (int i = 0; i < blocks.size(); i++) {
      number++;
      final NativeBlock nativeBlock = blocks.get(i);
      final long timestamp = schedule.timestamp(number);
      final BlockEnvironment environment =
          new BlockEnvironment(
              parent,
              timestamp,
              randao.seedFor(schedule.intervalOpeningSyncTimestamp(number)),
              nativeBlock.beneficiary(),
              nativeBlock.extraData());
      final boolean terminal = i == blocks.size() - 1;
      if (!terminal) {
        final BuiltBlock built = build(environment, nativeBlock.transactions());
        engine.importBlock(built);
        parent = built.blockHash();
        continue;
      }
      return rebuildTerminal(
          settlementBlock,
          context,
          number,
          environment,
          nativeBlock,
          h0,
          manifestSize,
          actions,
          consumptions);
    }
    throw new DerivationException("Empty Rollup0 span");
  }

  private Hash rebuildTerminal(
      final L1Block settlementBlock,
      final SettlementContext context,
      final long number,
      final BlockEnvironment environment,
      final NativeBlock nativeBlock,
      final Bytes32 h0,
      final int manifestSize,
      final List<InboundAction> actions,
      final List<Consumption> consumptions) {
    if (!schedule.isSync(number)) {
      throw new DerivationException("Rollup0 range ends at block " + number + ", not a Sync block");
    }
    // 10.2 step 12: live ends at the settlement slot, catch-up earlier with an empty manifest
    if (environment.timestamp() > settlementBlock.timestamp()) {
      throw new DerivationException("Rollup0 range ends after its settlement block");
    }
    if (environment.timestamp() < settlementBlock.timestamp() && manifestSize > 0) {
      throw new DerivationException("Catch-up anchor carries inbound actions");
    }
    final List<Bytes> transactions = new ArrayList<>(nativeBlock.transactions());
    BuiltBlock variant = build(environment, transactions);
    requireCommitment(variant, h0, 0);
    for (int k = 1; k <= actions.size(); k++) {
      final long remainingGas = EezChainConfig.GAS_LIMIT - variant.gasUsed();
      transactions.add(
          InboundTransactions.envelope(
              InboundTransactions.derive(deployment, context, actions.get(k - 1), remainingGas)));
      variant = build(environment, transactions);
      requireCommitment(variant, consumptions.get(k - 1).newRoot(), k);
    }
    engine.importBlock(variant);
    return variant.blockHash();
  }

  private BuiltBlock build(final BlockEnvironment environment, final List<Bytes> transactions) {
    try {
      return engine.build(environment, transactions);
    } catch (final EngineRpcException e) {
      throw new DerivationException("Cannot execute Rollup0 block: " + e.getMessage(), e);
    }
  }

  private static void requireCommitment(
      final BuiltBlock variant, final Bytes32 settled, final int k) {
    if (!variant.blockHash().getBytes().equals(settled)) {
      throw new DerivationException(
          "Rebuilt B["
              + k
              + "] is "
              + variant.blockHash()
              + " but settlement committed "
              + settled);
    }
  }
}
