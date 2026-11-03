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
package eez.besu.follower.l1;

import org.hyperledger.besu.datatypes.Hash;

import org.apache.tuweni.bytes.Bytes32;

/**
 * The settlement-chain block header fields the follower uses.
 *
 * @param number the block number
 * @param hash the block hash
 * @param parentHash the parent hash
 * @param timestamp the block timestamp
 * @param prevRandao the block's {@code prevRandao} ({@code mixHash})
 */
public record L1Block(
    long number, Hash hash, Hash parentHash, long timestamp, Bytes32 prevRandao) {}
