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

import com.fasterxml.jackson.databind.JsonNode;

/** Calls a JSON-RPC method on the local Besu node. */
@FunctionalInterface
public interface EngineRpc {

  /**
   * Calls a method.
   *
   * @param method the method name
   * @param params the positional parameters, as JSON-compatible values
   * @return the result
   * @throws EngineRpcException if the method returns an error
   */
  JsonNode call(String method, Object... params);
}
