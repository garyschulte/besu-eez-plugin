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
import org.hyperledger.besu.datatypes.VersionedHash;

import java.util.List;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;

/**
 * A settlement-chain transaction with its receipt status.
 *
 * @param hash the transaction hash
 * @param blockHash the containing block
 * @param transactionIndex the index in the block
 * @param from the sender
 * @param to the recipient
 * @param input the calldata
 * @param blobVersionedHashes the blob versioned hashes, empty for a non-blob transaction
 * @param successful whether the receipt status is success
 */
public record L1Transaction(
    Hash hash,
    Hash blockHash,
    int transactionIndex,
    Address from,
    Optional<Address> to,
    Bytes input,
    List<VersionedHash> blobVersionedHashes,
    boolean successful) {}
