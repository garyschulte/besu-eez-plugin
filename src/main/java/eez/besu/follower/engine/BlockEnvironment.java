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

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * The inputs of a Rollup0 header that are not derived from execution (draft 4.2). Number, gas
 * limit, base fee, and the zero beacon root, empty withdrawals and empty requests follow from the
 * parent and the chain rules.
 *
 * @param parentHash the parent block hash
 * @param timestamp the parent timestamp plus the block interval
 * @param prevRandao the interval seed copied from the settlement chain
 * @param beneficiary the composer-selected beneficiary from the blob payload
 * @param extraData the composer-selected extra data from the blob payload, at most 32 bytes
 */
public record BlockEnvironment(
    Hash parentHash, long timestamp, Bytes32 prevRandao, Address beneficiary, Bytes extraData) {}
