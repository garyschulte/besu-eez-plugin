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

import org.hyperledger.besu.plugin.services.RpcEndpointService;
import org.hyperledger.besu.plugin.services.rpc.PluginRpcResponse;
import org.hyperledger.besu.plugin.services.rpc.RpcResponseType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Calls Besu's own JSON-RPC methods in-process. Besu must enable them with {@code
 * --Xin-process-rpc-enabled --Xin-process-rpc-apis=ENGINE,TESTING,ETH}.
 */
public class InProcessEngineRpc implements EngineRpc {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final RpcEndpointService rpc;

  public InProcessEngineRpc(final RpcEndpointService rpc) {
    this.rpc = rpc;
  }

  @Override
  public JsonNode call(final String method, final Object... params) {
    final PluginRpcResponse response = rpc.call(method, params);
    final JsonNode result = MAPPER.valueToTree(response.getResult());
    if (response.getType() != RpcResponseType.SUCCESS) {
      throw new EngineRpcException(method + " failed: " + result);
    }
    return result;
  }
}
