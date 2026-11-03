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

import static eez.besu.follower.protocol.eez.AbiValues.bool;
import static eez.besu.follower.protocol.eez.AbiValues.bytes;
import static eez.besu.follower.protocol.eez.AbiValues.bytes32;
import static eez.besu.follower.protocol.eez.AbiValues.listOf;
import static eez.besu.follower.protocol.eez.AbiValues.toU64;
import static eez.besu.follower.protocol.eez.AbiValues.uint64;

import eez.besu.follower.protocol.eez.abi.Abi;
import eez.besu.follower.protocol.eez.abi.AbiType;

import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * A top-level L1 static (read-only) entry ({@code StaticExecutionEntry} in {@code IEEZ.sol}).
 *
 * @param expectedRoots live-root pins
 * @param proxyEntryHash inbound call hash
 * @param l2ToL1Calls read-only calls the entry executes on L1
 * @param rollingHash expected static rolling hash
 * @param destinationRollupId {@code uint64} static queue the entry is published to
 * @param success whether resolution returns or reverts
 * @param returnData return data, or revert data when {@code success} is false
 */
public record L1StaticExecutionEntry(
    List<ExpectedRoot> expectedRoots,
    Bytes32 proxyEntryHash,
    List<CrossChainCall> l2ToL1Calls,
    Bytes32 rollingHash,
    long destinationRollupId,
    boolean success,
    Bytes returnData) {

  /** The Solidity tuple type. */
  public static final AbiType.Tuple TYPE =
      AbiType.parseTuple(
          "("
              + ExpectedRoot.TYPE.canonical()
              + "[],bytes32,"
              + CrossChainCall.TYPE.canonical()
              + "[],bytes32,uint64,bool,bytes)");

  /** Copies the lists. */
  public L1StaticExecutionEntry {
    expectedRoots = List.copyOf(expectedRoots);
    l2ToL1Calls = List.copyOf(l2ToL1Calls);
  }

  /**
   * Returns {@code keccak256(abi.encode(staticEntry))}, the hash EEZ folds into the public input.
   */
  public Bytes32 hash() {
    return Abi.keccak256(Abi.encodeSingle(TYPE, toAbi()));
  }

  /** Returns the ABI value of this struct. */
  public List<Object> toAbi() {
    return List.of(
        AbiValues.toAbi(expectedRoots, ExpectedRoot::toAbi),
        proxyEntryHash,
        AbiValues.toAbi(l2ToL1Calls, CrossChainCall::toAbi),
        rollingHash,
        uint64(destinationRollupId),
        success,
        returnData);
  }

  /**
   * Builds the struct from its decoded ABI value.
   *
   * @param v the decoded tuple
   * @return the struct
   */
  public static L1StaticExecutionEntry fromAbi(final List<?> v) {
    return new L1StaticExecutionEntry(
        listOf(v.get(0), ExpectedRoot::fromAbi),
        bytes32(v.get(1)),
        listOf(v.get(2), CrossChainCall::fromAbi),
        bytes32(v.get(3)),
        toU64(v.get(4)),
        bool(v.get(5)),
        bytes(v.get(6)));
  }
}
