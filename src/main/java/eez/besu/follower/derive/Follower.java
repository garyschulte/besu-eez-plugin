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

import eez.besu.follower.engine.EngineDriver;
import eez.besu.follower.l1.L1Block;
import eez.besu.follower.l1.L1Client;
import eez.besu.follower.l1.L1Exception;
import eez.besu.follower.l1.L1Log;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Follows the settlement chain and moves this node's Rollup0 safe and finalized blocks with it
 * (draft 10.2, 10.5, 10.6).
 *
 * <p>Each poll reads the EEZ contract's logs in canonical order from the last processed settlement
 * block, hands each settlement transaction to the {@link SettlementDeriver}, and records every
 * advance of the Rollup0 cursor as a checkpoint. A checkpoint becomes finalized when its settlement
 * block is finalized. On a settlement-chain reorganization the follower drops the checkpoints of
 * orphaned blocks and retreats the safe view to the last one that survives; the finalized view
 * never retreats, and a reorganization below it halts the follower.
 *
 * <p>Everything runs on one thread, which serializes the Engine API calls.
 */
public class Follower {

  private static final Logger LOG = LoggerFactory.getLogger(Follower.class);
  private static final long MAX_LOG_RANGE = 1_000L;

  private final L1Client l1;
  private final Address eezAddress;
  private final SettlementDeriver deriver;
  private final EngineDriver engine;
  private final RandaoSource randao;
  private final Path stateFile;
  private final Duration pollInterval;
  private final FollowerState state;
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          r -> Thread.ofPlatform().name("eez-follower").daemon().unstarted(r));
  private volatile boolean halted;

  public Follower(
      final L1Client l1,
      final Address eezAddress,
      final SettlementDeriver deriver,
      final EngineDriver engine,
      final RandaoSource randao,
      final Path stateFile,
      final Duration pollInterval,
      final FollowerState initialState) {
    this.l1 = l1;
    this.eezAddress = eezAddress;
    this.deriver = deriver;
    this.engine = engine;
    this.randao = randao;
    this.stateFile = stateFile;
    this.pollInterval = pollInterval;
    this.state = FollowerState.load(stateFile).orElse(initialState);
  }

  public void start() {
    LOG.info(
        "EEZ follower starting after settlement block {}, safe head {}",
        state.l1CursorNumber,
        state.safeHead());
    executor.scheduleWithFixedDelay(
        this::pollSafely, 0, pollInterval.toMillis(), TimeUnit.MILLISECONDS);
  }

  public void stop() {
    executor.shutdownNow();
  }

  /** Whether derivation stopped on data it could not reconcile. */
  public boolean isHalted() {
    return halted;
  }

  private void pollSafely() {
    if (halted) {
      return;
    }
    try {
      poll();
    } catch (final L1Exception e) {
      LOG.warn("Settlement chain unavailable, retrying: {}", e.getMessage());
    } catch (final DerivationException e) {
      halted = true;
      LOG.error("EEZ derivation halted: {}", e.getMessage(), e);
    } catch (final RuntimeException e) {
      halted = true;
      LOG.error("EEZ follower failed", e);
    }
  }

  /** One pass: handle reorganizations, derive new settlements, then advance finality. */
  void poll() {
    handleReorg();
    final L1Block latest = l1.block("latest");
    while (state.l1CursorNumber < latest.number()) {
      final long from = state.l1CursorNumber + 1;
      final long to = Math.min(latest.number(), from + MAX_LOG_RANGE - 1);
      deriveRange(from, to);
      final L1Block cursor =
          l1.block(to).orElseThrow(() -> new L1Exception("Settlement block " + to + " missing"));
      state.l1CursorNumber = cursor.number();
      state.l1CursorHash = cursor.hash().toHexString();
      state.save(stateFile);
    }
    advanceFinality();
  }

  private void deriveRange(final long from, final long to) {
    final Map<Hash, List<L1Log>> byBlock = new LinkedHashMap<>();
    for (final L1Log log : l1.logs(eezAddress, from, to)) {
      byBlock.computeIfAbsent(log.blockHash(), h -> new ArrayList<>()).add(log);
    }
    for (final List<L1Log> logs : byBlock.values()) {
      final L1Log first = logs.getFirst();
      final L1Block block =
          l1.block(first.blockNumber())
              .filter(b -> b.hash().equals(first.blockHash()))
              .orElseThrow(() -> new L1Exception("Settlement block reorganized during read"));
      final Hash safe = state.safeHead();
      deriver
          .derive(block, logs, safe)
          .ifPresent(
              newSafe -> {
                state.checkpoints.add(
                    new FollowerState.Checkpoint(
                        block.number(), block.hash().toHexString(), newSafe.toHexString()));
                engine.forkchoice(newSafe, newSafe, state.finalizedHead());
                state.save(stateFile);
                LOG.info("Rollup0 safe head {} from settlement block {}", newSafe, block.number());
              });
    }
  }

  private void advanceFinality() {
    final L1Block finalized = l1.block("finalized");
    Hash newFinalized = state.finalizedHead();
    for (final FollowerState.Checkpoint checkpoint : state.checkpoints) {
      if (checkpoint.l1Number() <= finalized.number()) {
        newFinalized = checkpoint.safeHeadHash();
      }
    }
    if (!newFinalized.equals(state.finalizedHead())) {
      state.finalizedHead = newFinalized.toHexString();
      engine.forkchoice(state.safeHead(), state.safeHead(), newFinalized);
      state.save(stateFile);
      LOG.info("Rollup0 finalized head {}", newFinalized);
    }
  }

  /** Retreats past settlement blocks that are no longer canonical (draft 10.6). */
  private void handleReorg() {
    if (state.l1CursorHash == null || isCanonical(state.l1CursorNumber, state.l1CursorHash)) {
      return;
    }
    final L1Block finalized = l1.block("finalized");
    final Iterator<FollowerState.Checkpoint> newestFirst = state.checkpoints.reversed().iterator();
    long survivingL1 = -1;
    while (newestFirst.hasNext()) {
      final FollowerState.Checkpoint checkpoint = newestFirst.next();
      if (isCanonical(checkpoint.l1Number(), checkpoint.l1Hash())) {
        survivingL1 = checkpoint.l1Number();
        break;
      }
      if (checkpoint.l1Number() <= finalized.number()) {
        throw new DerivationException(
            "Settlement block "
                + checkpoint.l1Number()
                + " was finalized but is no longer canonical; recovery requires a hardfork");
      }
      newestFirst.remove();
    }
    // Finalized blocks are canonical, so any settlement at or below finality survived as a
    // checkpoint: everything up to the newer of the two has been derived correctly already.
    state.l1CursorNumber =
        Math.max(survivingL1, Math.min(state.l1CursorNumber, finalized.number()));
    state.l1CursorHash =
        l1.block(state.l1CursorNumber).map(b -> b.hash().toHexString()).orElse(null);
    randao.invalidate();
    engine.forkchoice(state.safeHead(), state.safeHead(), state.finalizedHead());
    state.save(stateFile);
    LOG.warn(
        "Settlement chain reorganized; Rollup0 safe head retreats to {}, resuming after block {}",
        state.safeHead(),
        state.l1CursorNumber);
  }

  private boolean isCanonical(final long number, final String hash) {
    return l1.block(number).map(b -> b.hash().toHexString().equals(hash)).orElse(false);
  }
}
