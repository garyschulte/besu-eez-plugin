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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;

class EngineDriverTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Hash PARENT = Hash.fromHexString("0x" + "aa".repeat(32));
  private static final Hash BUILT = Hash.fromHexString("0x" + "bb".repeat(32));

  private final List<Object[]> calls = new ArrayList<>();
  private JsonNode nextResult;
  private final EngineDriver driver =
      new EngineDriver(
          (method, params) -> {
            calls.add(new Object[] {method, params});
            return nextResult;
          });

  @Test
  void buildsWithTheRollup0HeaderInputs() throws Exception {
    nextResult =
        MAPPER.readTree(
            "{\"executionPayload\":{\"blockHash\":\""
                + BUILT.toHexString()
                + "\",\"blockNumber\":\"0x7\",\"gasUsed\":\"0x5208\"}}");

    final BuiltBlock block =
        driver.build(
            new BlockEnvironment(
                PARENT,
                0x6553f10cL,
                Bytes32.repeat((byte) 0x42),
                Address.fromHexString("0x00000000000000000000000000000000000000c0"),
                Bytes.fromHexString("0x0102")),
            List.of(Bytes.fromHexString("0x45c0")));

    assertThat(block.blockHash()).isEqualTo(BUILT);
    assertThat(block.blockNumber()).isEqualTo(7);
    assertThat(block.gasUsed()).isEqualTo(21_000);
    final Object[] call = calls.getFirst();
    assertThat(call[0]).isEqualTo("testing_buildBlockV1");
    final Object[] params = (Object[]) call[1];
    assertThat(params[0]).isEqualTo(PARENT.toHexString());
    @SuppressWarnings("unchecked")
    final Map<String, Object> attributes = (Map<String, Object>) params[1];
    assertThat(attributes)
        .containsEntry("timestamp", "0x6553f10c")
        .containsEntry("prevRandao", Bytes32.repeat((byte) 0x42).toHexString())
        .containsEntry("suggestedFeeRecipient", "0x00000000000000000000000000000000000000c0")
        .containsEntry("withdrawals", List.of())
        .containsEntry("parentBeaconBlockRoot", Bytes32.ZERO.toHexString())
        .containsEntry("targetGasLimit", "0x1c9c380");
    assertThat(params[2]).isEqualTo(List.of("0x45c0"));
    assertThat(params[3]).isEqualTo("0x0102");
  }

  @Test
  void importRequiresAValidStatus() throws Exception {
    final BuiltBlock block =
        new BuiltBlock(BUILT, 7, 0, MAPPER.readTree("{\"blockHash\":\"" + BUILT + "\"}"));
    nextResult = MAPPER.readTree("{\"status\":\"INVALID\",\"validationError\":\"bad\"}");

    assertThatThrownBy(() -> driver.importBlock(block)).isInstanceOf(EngineRpcException.class);

    nextResult = MAPPER.readTree("{\"status\":\"VALID\"}");
    driver.importBlock(block);
    final Object[] params = (Object[]) calls.getLast()[1];
    assertThat(calls.getLast()[0]).isEqualTo("engine_newPayloadV4");
    assertThat(params[1]).isEqualTo(List.of());
    assertThat(params[2]).isEqualTo(Bytes32.ZERO.toHexString());
    assertThat(params[3]).isEqualTo(List.of());
  }

  @Test
  void forkchoiceSetsAllThreeHeads() throws Exception {
    nextResult = MAPPER.readTree("{\"payloadStatus\":{\"status\":\"VALID\"}}");

    driver.forkchoice(BUILT, PARENT, Hash.ZERO);

    final Object[] params = (Object[]) calls.getLast()[1];
    assertThat(calls.getLast()[0]).isEqualTo("engine_forkchoiceUpdatedV3");
    assertThat(params[0])
        .isEqualTo(
            Map.of(
                "headBlockHash", BUILT.toHexString(),
                "safeBlockHash", PARENT.toHexString(),
                "finalizedBlockHash", Hash.ZERO.toHexString()));
    assertThat(params[1]).isNull();
  }
}
