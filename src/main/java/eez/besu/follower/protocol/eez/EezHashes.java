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

import static eez.besu.follower.protocol.eez.AbiValues.uint64;

import eez.besu.follower.protocol.eez.abi.Abi;
import eez.besu.follower.protocol.eez.abi.AbiType;

import org.hyperledger.besu.datatypes.Address;

import java.math.BigInteger;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/** The EEZ cross-chain call hash and rolling-hash folds ({@code EEZBase.sol} at rev 84a5b52). */
public final class EezHashes {

  private static final AbiType.Tuple CALL_HASH_TYPE =
      AbiType.parseTuple("(bool,address,uint64,address,uint64,uint256,uint64,bytes)");

  private static final byte CALL_BEGIN = 1;
  private static final byte CALL_END = 2;
  private static final byte NESTED_BEGIN = 3;
  private static final byte NESTED_END = 4;

  private EezHashes() {}

  /**
   * {@code computeCrossChainCallHash}: {@code keccak256(abi.encode(isStatic, sourceAddress,
   * sourceRollupId, targetAddress, targetRollupId, value, callGas, data))}.
   *
   * @param isStatic whether the call is read-only
   * @param sourceAddress caller on the source network
   * @param sourceRollupId {@code uint64} source network id
   * @param targetAddress destination on the target network
   * @param targetRollupId {@code uint64} target network id
   * @param value wei bound to the call
   * @param callGas {@code uint64} gas term; 0 on every L1 path
   * @param data calldata bound to the call
   * @return the call hash
   */
  public static Bytes32 crossChainCallHash(
      final boolean isStatic,
      final Address sourceAddress,
      final long sourceRollupId,
      final Address targetAddress,
      final long targetRollupId,
      final BigInteger value,
      final long callGas,
      final Bytes data) {
    return Abi.keccak256(
        Abi.encode(
            CALL_HASH_TYPE,
            List.of(
                isStatic,
                sourceAddress,
                uint64(sourceRollupId),
                targetAddress,
                uint64(targetRollupId),
                value,
                uint64(callGas),
                data)));
  }

  /**
   * The hash an L1 trigger produces for a mutable call from the settlement L1 to Rollup0: the value
   * EEZ emits in {@code ExecutionConsumed} and stores as the deferred entry's {@code
   * proxyEntryHash}, and the {@code crossChainCallHash} input of {@code sourceHash} (F.3). L1
   * always folds {@code callGas = 0}.
   *
   * @param sourceAddress the L1 caller of the Rollup0 proxy
   * @param targetAddress the Rollup0 destination
   * @param rollup0EezRollupId Rollup0's EEZ rollup id
   * @param value wei sent with the call
   * @param data calldata sent to the proxy
   * @return the call hash
   */
  public static Bytes32 inboundCallHash(
      final Address sourceAddress,
      final Address targetAddress,
      final long rollup0EezRollupId,
      final BigInteger value,
      final Bytes data) {
    return crossChainCallHash(
        false,
        sourceAddress,
        Rollup0Constants.SETTLEMENT_L1_ROLLUP_ID,
        targetAddress,
        rollup0EezRollupId,
        value,
        0L,
        data);
  }

  /**
   * L1 entry seed ({@code _rollingHashEntryBegin}): {@code keccak(…keccak(0 ‖ rollupId_1 ‖
   * currentRoot_1)…) ‖ proxyEntryHash}, with {@code rollupId} packed as 8 bytes.
   *
   * @param rollupUpdates the entry's updates in order
   * @param proxyEntryHash the entry's identity
   * @return the initial rolling hash
   */
  public static Bytes32 l1EntrySeed(
      final List<RollupUpdate> rollupUpdates, final Bytes32 proxyEntryHash) {
    Bytes32 roots = Bytes32.ZERO;
    for (final RollupUpdate update : rollupUpdates) {
      roots =
          Abi.keccak256(
              Bytes.concatenate(
                  roots, Bytes.ofUnsignedLong(update.rollupId()), update.currentRoot()));
    }
    return Abi.keccak256(Bytes.concatenate(roots, proxyEntryHash));
  }

  /**
   * L2 entry seed ({@code EEZL2._seedRollingHash}): {@code keccak256(bytes32(0) ‖ proxyEntryHash)}.
   *
   * @param proxyEntryHash the entry's identity
   * @return the initial rolling hash
   */
  public static Bytes32 l2EntrySeed(final Bytes32 proxyEntryHash) {
    return Abi.keccak256(Bytes.concatenate(Bytes32.ZERO, proxyEntryHash));
  }

  /**
   * {@code CALL_BEGIN}: {@code keccak256(prev ‖ 0x01 ‖ crossChainCallHash)}.
   *
   * @param previous the rolling hash before the call
   * @param callHash the call's identity, folded with {@code callGas = 0}
   * @return the new rolling hash
   */
  public static Bytes32 callBegin(final Bytes32 previous, final Bytes32 callHash) {
    return Abi.keccak256(Bytes.concatenate(previous, Bytes.of(CALL_BEGIN), callHash));
  }

  /**
   * {@code CALL_END}: {@code keccak256(prev ‖ 0x02 ‖ success ‖ returnData)}.
   *
   * @param previous the rolling hash before the fold
   * @param success whether the call succeeded
   * @param returnData the raw return or revert data
   * @return the new rolling hash
   */
  public static Bytes32 callEnd(
      final Bytes32 previous, final boolean success, final Bytes returnData) {
    return Abi.keccak256(
        Bytes.concatenate(previous, Bytes.of(CALL_END), Bytes.of(success ? 1 : 0), returnData));
  }

  /**
   * {@code NESTED_BEGIN}: {@code keccak256(prev ‖ 0x03 ‖ crossChainCallHash)}.
   *
   * @param previous the rolling hash before the frame
   * @param callHash the reentrant call's identity
   * @return the new rolling hash
   */
  public static Bytes32 nestedBegin(final Bytes32 previous, final Bytes32 callHash) {
    return Abi.keccak256(Bytes.concatenate(previous, Bytes.of(NESTED_BEGIN), callHash));
  }

  /**
   * {@code NESTED_END}: {@code keccak256(prev ‖ 0x04)}.
   *
   * @param previous the rolling hash before the fold
   * @return the new rolling hash
   */
  public static Bytes32 nestedEnd(final Bytes32 previous) {
    return Abi.keccak256(Bytes.concatenate(previous, Bytes.of(NESTED_END)));
  }
}
