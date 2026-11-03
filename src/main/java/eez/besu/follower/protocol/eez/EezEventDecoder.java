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
package eez.besu.follower.protocol.eez;

import static eez.besu.follower.protocol.eez.AbiValues.address;
import static eez.besu.follower.protocol.eez.AbiValues.bytes;
import static eez.besu.follower.protocol.eez.AbiValues.bytes32;
import static eez.besu.follower.protocol.eez.AbiValues.integer;
import static eez.besu.follower.protocol.eez.AbiValues.toU64;
import static eez.besu.follower.protocol.eez.AbiValues.u64List;

import eez.besu.follower.protocol.eez.abi.AbiDecodingException;
import eez.besu.follower.protocol.eez.abi.AbiEvent;

import org.hyperledger.besu.datatypes.Address;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/** Decodes logs of the selected L1 {@code EEZ} deployment into {@link EezEvent}s. */
public final class EezEventDecoder {

  /** {@code BatchPosted(bytes32,uint64[])}. */
  public static final AbiEvent BATCH_POSTED = AbiEvent.parse("BatchPosted(bytes32,uint64[])");

  /** {@code ExecutionConsumed(bytes32,uint64,uint256)}, all indexed. */
  public static final AbiEvent EXECUTION_CONSUMED =
      AbiEvent.parse("ExecutionConsumed(bytes32 indexed,uint64 indexed,uint256 indexed)");

  /** {@code L2ExecutionPerformed(uint64,bytes32,uint256)}, rollup id indexed. */
  public static final AbiEvent L2_EXECUTION_PERFORMED =
      AbiEvent.parse("L2ExecutionPerformed(uint64 indexed,bytes32,uint256)");

  /** {@code L2TxSkipped(uint256,bytes)}, entry index indexed. */
  public static final AbiEvent L2_TX_SKIPPED = AbiEvent.parse("L2TxSkipped(uint256 indexed,bytes)");

  /** {@code RootUpdated(uint64,bytes32)}, rollup id indexed. */
  public static final AbiEvent ROOT_UPDATED = AbiEvent.parse("RootUpdated(uint64 indexed,bytes32)");

  /** {@code RollupCreated(uint64,address,bytes32)}, id and contract indexed. */
  public static final AbiEvent ROLLUP_CREATED =
      AbiEvent.parse("RollupCreated(uint64 indexed,address indexed,bytes32)");

  private static final Map<Bytes32, Decoder> DECODERS =
      Map.of(
          BATCH_POSTED.topic0(),
          new Decoder(
              BATCH_POSTED, v -> new EezEvent.BatchPosted(bytes32(v.get(0)), u64List(v.get(1)))),
          EXECUTION_CONSUMED.topic0(),
          new Decoder(
              EXECUTION_CONSUMED,
              v ->
                  new EezEvent.ExecutionConsumed(
                      bytes32(v.get(0)), toU64(v.get(1)), integer(v.get(2)))),
          L2_EXECUTION_PERFORMED.topic0(),
          new Decoder(
              L2_EXECUTION_PERFORMED,
              v ->
                  new EezEvent.L2ExecutionPerformed(
                      toU64(v.get(0)), bytes32(v.get(1)), integer(v.get(2)))),
          L2_TX_SKIPPED.topic0(),
          new Decoder(
              L2_TX_SKIPPED, v -> new EezEvent.L2TxSkipped(integer(v.get(0)), bytes(v.get(1)))),
          ROOT_UPDATED.topic0(),
          new Decoder(
              ROOT_UPDATED, v -> new EezEvent.RootUpdated(toU64(v.get(0)), bytes32(v.get(1)))),
          ROLLUP_CREATED.topic0(),
          new Decoder(
              ROLLUP_CREATED,
              v ->
                  new EezEvent.RollupCreated(
                      toU64(v.get(0)), address(v.get(1)), bytes32(v.get(2)))));

  private final Address eez;

  /**
   * Creates a decoder that accepts logs only from one EEZ deployment (10.1).
   *
   * @param eez the selected L1 EEZ contract
   */
  public EezEventDecoder(final Address eez) {
    this.eez = eez;
  }

  /**
   * Decodes one log.
   *
   * @param emitter the log's address
   * @param topics the log's topics
   * @param data the log's data
   * @return the event, or empty when the log is from another contract or is not one of the events
   *     above
   * @throws AbiDecodingException if topic0 matches a known event but the log is malformed
   */
  public Optional<EezEvent> decode(
      final Address emitter, final List<Bytes32> topics, final Bytes data) {
    if (!eez.equals(emitter) || topics.isEmpty()) {
      return Optional.empty();
    }
    final Decoder decoder = DECODERS.get(topics.getFirst());
    if (decoder == null) {
      return Optional.empty();
    }
    return Optional.of(decoder.build().apply(decoder.event().decode(topics, data)));
  }

  private record Decoder(AbiEvent event, Function<List<Object>, EezEvent> build) {}
}
