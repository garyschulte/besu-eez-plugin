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
import static eez.besu.follower.protocol.eez.AbiValues.integer;
import static eez.besu.follower.protocol.eez.AbiValues.listOf;

import eez.besu.follower.protocol.eez.abi.AbiType;

import java.math.BigInteger;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * A top-level {@code EEZL2} static entry ({@code StaticExecutionEntryL2} in {@code IEEZL2.sol}).
 *
 * @param expectedEntryIndex {@code uint256} execution cursor the entry is pinned to
 * @param proxyEntryHash inbound call hash
 * @param incomingCalls read-only calls the entry executes
 * @param rollingHash expected static rolling hash
 * @param success whether resolution returns or reverts
 * @param returnData return data, or revert data when {@code success} is false
 */
public record L2StaticExecutionEntry(
    BigInteger expectedEntryIndex,
    Bytes32 proxyEntryHash,
    List<CrossChainCall> incomingCalls,
    Bytes32 rollingHash,
    boolean success,
    Bytes returnData) {

  /** The Solidity tuple type. */
  public static final AbiType.Tuple TYPE =
      AbiType.parseTuple(
          "(uint256,bytes32," + CrossChainCall.TYPE.canonical() + "[],bytes32,bool,bytes)");

  /** Copies the list. */
  public L2StaticExecutionEntry {
    incomingCalls = List.copyOf(incomingCalls);
  }

  /** Returns the ABI value of this struct. */
  public List<Object> toAbi() {
    return List.of(
        expectedEntryIndex,
        proxyEntryHash,
        AbiValues.toAbi(incomingCalls, CrossChainCall::toAbi),
        rollingHash,
        success,
        returnData);
  }

  /**
   * Builds the struct from its decoded ABI value.
   *
   * @param v the decoded tuple
   * @return the struct
   */
  public static L2StaticExecutionEntry fromAbi(final List<?> v) {
    return new L2StaticExecutionEntry(
        integer(v.get(0)),
        bytes32(v.get(1)),
        listOf(v.get(2), CrossChainCall::fromAbi),
        bytes32(v.get(3)),
        bool(v.get(4)),
        bytes(v.get(5)));
  }
}
