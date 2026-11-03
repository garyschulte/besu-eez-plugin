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

import org.hyperledger.besu.datatypes.Hash;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What the follower has derived, persisted so it can resume. Rollup0 blocks themselves live in
 * Besu's chain; this records only how far the settlement chain has been read and which Rollup0
 * block each settlement made safe, which is what a settlement-chain reorganization retreats
 * through.
 */
public class FollowerState {

  /**
   * A settlement that advanced the Rollup0 cursor.
   *
   * @param l1Number the settlement block number
   * @param l1Hash the settlement block hash
   * @param safeHead the Rollup0 block it made safe
   */
  public record Checkpoint(
      @JsonProperty("l1Number") long l1Number,
      @JsonProperty("l1Hash") String l1Hash,
      @JsonProperty("safeHead") String safeHead) {

    Hash l1BlockHash() {
      return Hash.fromHexString(l1Hash);
    }

    Hash safeHeadHash() {
      return Hash.fromHexString(safeHead);
    }
  }

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @JsonProperty long l1CursorNumber;
  @JsonProperty String l1CursorHash;
  @JsonProperty String genesisHash;
  @JsonProperty String finalizedHead;
  @JsonProperty List<Checkpoint> checkpoints = new ArrayList<>();

  public static FollowerState initial(final Hash genesis, final long settlementStartBlock) {
    final FollowerState state = new FollowerState();
    state.genesisHash = genesis.toHexString();
    state.finalizedHead = genesis.toHexString();
    state.l1CursorNumber = settlementStartBlock - 1;
    return state;
  }

  static Optional<FollowerState> load(final Path file) {
    if (!Files.exists(file)) {
      return Optional.empty();
    }
    try {
      return Optional.of(MAPPER.readValue(file.toFile(), FollowerState.class));
    } catch (final IOException e) {
      throw new IllegalStateException("Cannot read follower state " + file, e);
    }
  }

  void save(final Path file) {
    try {
      final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
      MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), this);
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (final IOException e) {
      throw new IllegalStateException("Cannot write follower state " + file, e);
    }
  }

  Hash safeHead() {
    return checkpoints.isEmpty()
        ? Hash.fromHexString(genesisHash)
        : checkpoints.getLast().safeHeadHash();
  }

  Hash finalizedHead() {
    return Hash.fromHexString(finalizedHead);
  }
}
