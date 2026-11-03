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

import org.hyperledger.besu.datatypes.Hash;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A block Besu built and executed from an exact transaction list, not yet imported.
 *
 * @param blockHash the block hash
 * @param blockNumber the block number
 * @param gasUsed the gas the block used
 * @param executionPayload the Engine API execution payload, ready for {@code engine_newPayload}
 */
public record BuiltBlock(
    Hash blockHash, long blockNumber, long gasUsed, JsonNode executionPayload) {}
