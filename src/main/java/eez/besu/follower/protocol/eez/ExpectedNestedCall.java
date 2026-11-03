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

import eez.besu.follower.protocol.eez.abi.AbiType;

import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * The precomputed result of a reentrant call: L1 {@code ExpectedL1ToL2Call} and L2 {@code
 * ExpectedOutgoingCrossChainCall} share this exact layout.
 *
 * @param positionKey {@code keccak256(crossChainCallHash ‖ rollingHash)} at the fire point
 * @param calls the frame's own sub-calls
 * @param revertedOrStaticRollingHash expected sub-call hash for static or reverted frames
 * @param success whether the reentrant call returns or reverts
 * @param returnData the precomputed return or revert data
 */
public record ExpectedNestedCall(
    Bytes32 positionKey,
    List<CrossChainCall> calls,
    Bytes32 revertedOrStaticRollingHash,
    boolean success,
    Bytes returnData) {

  /** {@code (bytes32,CrossChainCall[],bytes32,bool,bytes)}. */
  public static final AbiType.Tuple TYPE =
      AbiType.parseTuple("(bytes32," + CrossChainCall.TYPE.canonical() + "[],bytes32,bool,bytes)");

  /** Copies the list. */
  public ExpectedNestedCall {
    calls = List.copyOf(calls);
  }

  /** Returns the ABI value of this struct. */
  public List<Object> toAbi() {
    return List.of(
        positionKey,
        AbiValues.toAbi(calls, CrossChainCall::toAbi),
        revertedOrStaticRollingHash,
        success,
        returnData);
  }

  /**
   * Builds the struct from its decoded ABI value.
   *
   * @param v the decoded tuple
   * @return the struct
   */
  public static ExpectedNestedCall fromAbi(final List<?> v) {
    return new ExpectedNestedCall(
        bytes32(v.get(0)),
        listOf(v.get(1), CrossChainCall::fromAbi),
        bytes32(v.get(2)),
        bool(v.get(3)),
        bytes(v.get(4)));
  }
}
