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

import static eez.besu.follower.protocol.eez.AbiValues.address;
import static eez.besu.follower.protocol.eez.AbiValues.bool;
import static eez.besu.follower.protocol.eez.AbiValues.bytes;
import static eez.besu.follower.protocol.eez.AbiValues.integer;
import static eez.besu.follower.protocol.eez.AbiValues.toU16;
import static eez.besu.follower.protocol.eez.AbiValues.toU64;
import static eez.besu.follower.protocol.eez.AbiValues.uint64;

import eez.besu.follower.protocol.eez.abi.AbiType;

import org.hyperledger.besu.datatypes.Address;

import java.math.BigInteger;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;

/**
 * One cross-chain call executed on the destination network: L1 {@code L2ToL1Call} and L2 {@code
 * CrossChainCall} share this exact layout ({@code IEEZ.sol}, {@code IEEZL2.sol}).
 *
 * @param revertNextNCalls {@code uint16} force-revert span length starting at this call, 0 = none
 * @param isStatic whether the call executes as a STATICCALL
 * @param gas {@code uint64} gas cap for the target call, 0 = forward all
 * @param sourceAddress caller on the source network
 * @param sourceRollupId {@code uint64} source network id (0 = settlement L1)
 * @param targetAddress destination contract on this network
 * @param value {@code uint256} wei sent with the call
 * @param data calldata for the target
 */
public record CrossChainCall(
    int revertNextNCalls,
    boolean isStatic,
    long gas,
    Address sourceAddress,
    long sourceRollupId,
    Address targetAddress,
    BigInteger value,
    Bytes data) {

  /** {@code (uint16,bool,uint64,address,uint64,address,uint256,bytes)}. */
  public static final AbiType.Tuple TYPE =
      AbiType.parseTuple("(uint16,bool,uint64,address,uint64,address,uint256,bytes)");

  /** Returns the ABI value of this struct. */
  public List<Object> toAbi() {
    return List.of(
        BigInteger.valueOf(revertNextNCalls),
        isStatic,
        uint64(gas),
        sourceAddress,
        uint64(sourceRollupId),
        targetAddress,
        value,
        data);
  }

  /**
   * Builds the struct from its decoded ABI value.
   *
   * @param v the decoded tuple
   * @return the struct
   */
  public static CrossChainCall fromAbi(final List<?> v) {
    return new CrossChainCall(
        toU16(v.get(0)),
        bool(v.get(1)),
        toU64(v.get(2)),
        address(v.get(3)),
        toU64(v.get(4)),
        address(v.get(5)),
        integer(v.get(6)),
        bytes(v.get(7)));
  }
}
