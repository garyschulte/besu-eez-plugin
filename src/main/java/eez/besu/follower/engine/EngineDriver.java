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
package eez.besu.follower.engine;

import eez.besu.follower.chain.EezChainConfig;

import org.hyperledger.besu.datatypes.Hash;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Drives the local Besu node the way a consensus client does, through the Engine API profile the
 * draft fixes (fcuV3, newPayloadV4), plus {@code testing_buildBlockV1} to execute an exact
 * transaction list on a parent without importing it. All calls go through one instance, which the
 * follower uses from a single thread, so they are serialized.
 */
public class EngineDriver {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String ZERO_ROOT = Bytes32.ZERO.toHexString();

  private final EngineRpc rpc;

  public EngineDriver(final EngineRpc rpc) {
    this.rpc = rpc;
  }

  /**
   * Builds and executes a block on its parent from exactly these transactions, in order. Besu
   * reports an error if any transaction cannot be included, so a returned block contains all of
   * them.
   *
   * @param environment the header inputs
   * @param transactions the canonical EIP-2718 encodings
   * @return the built block
   * @throws EngineRpcException if Besu cannot build the block
   */
  public BuiltBlock build(final BlockEnvironment environment, final List<Bytes> transactions) {
    final Map<String, Object> attributes = new HashMap<>();
    attributes.put("timestamp", quantity(environment.timestamp()));
    attributes.put("prevRandao", environment.prevRandao().toHexString());
    attributes.put("suggestedFeeRecipient", environment.beneficiary().toHexString());
    attributes.put("withdrawals", List.of());
    attributes.put("parentBeaconBlockRoot", ZERO_ROOT);
    attributes.put("targetGasLimit", quantity(EezChainConfig.GAS_LIMIT));
    final JsonNode result =
        rpc.call(
            "testing_buildBlockV1",
            environment.parentHash().toHexString(),
            attributes,
            transactions.stream().map(Bytes::toHexString).toList(),
            environment.extraData().toHexString());
    final JsonNode payload = result.get("executionPayload");
    if (payload == null) {
      throw new EngineRpcException("testing_buildBlockV1 returned no executionPayload: " + result);
    }
    return new BuiltBlock(
        Hash.fromHexString(payload.get("blockHash").asText()),
        Long.decode(payload.get("blockNumber").asText()),
        Long.decode(payload.get("gasUsed").asText()),
        payload);
  }

  /**
   * Imports a block.
   *
   * @param block the block
   * @throws EngineRpcException if Besu does not accept the block as valid
   */
  public void importBlock(final BuiltBlock block) {
    final JsonNode status =
        rpc.call(
            "engine_newPayloadV4",
            MAPPER.convertValue(block.executionPayload(), Map.class),
            List.of(),
            ZERO_ROOT,
            List.of());
    final String verdict = status.path("status").asText();
    if (!"VALID".equals(verdict)) {
      throw new EngineRpcException(
          "engine_newPayloadV4 did not accept block " + block.blockHash() + ": " + status);
    }
  }

  /**
   * Moves the head, safe and finalized blocks.
   *
   * @param head the new head
   * @param safe the new safe block
   * @param finalized the new finalized block
   * @throws EngineRpcException if Besu does not accept the fork choice
   */
  public void forkchoice(final Hash head, final Hash safe, final Hash finalized) {
    final JsonNode result =
        rpc.call(
            "engine_forkchoiceUpdatedV3",
            Map.of(
                "headBlockHash", head.toHexString(),
                "safeBlockHash", safe.toHexString(),
                "finalizedBlockHash", finalized.toHexString()),
            null);
    final String verdict = result.path("payloadStatus").path("status").asText();
    if (!"VALID".equals(verdict)) {
      throw new EngineRpcException("engine_forkchoiceUpdatedV3 rejected " + head + ": " + result);
    }
  }

  private static String quantity(final long value) {
    return "0x" + Long.toHexString(value);
  }
}
