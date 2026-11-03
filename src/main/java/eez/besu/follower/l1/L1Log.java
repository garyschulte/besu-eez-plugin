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

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;

import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * A settlement-chain log with its canonical position.
 *
 * @param address the emitting contract
 * @param topics the topics
 * @param data the data
 * @param blockNumber the block number
 * @param blockHash the block hash
 * @param transactionHash the transaction hash
 * @param transactionIndex the transaction index in the block
 * @param logIndex the log index in the block
 */
public record L1Log(
    Address address,
    List<Bytes32> topics,
    Bytes data,
    long blockNumber,
    Hash blockHash,
    Hash transactionHash,
    int transactionIndex,
    int logIndex) {}
