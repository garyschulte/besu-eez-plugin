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

import org.apache.tuweni.bytes.Bytes32;

/** The type-0x45 {@code sourceDomain} and {@code sourceHash} (Appendix F.3). */
public final class InboundSource {

  private static final AbiType.Tuple DOMAIN_TYPE =
      AbiType.parseTuple("(bytes32,uint256,uint256,address,uint256,uint256)");
  private static final AbiType.Tuple SOURCE_TYPE =
      AbiType.parseTuple("(bytes32,uint256,bytes32,uint256,bytes32)");

  private InboundSource() {}

  /**
   * {@code keccak256(abi.encode(INBOUND_SOURCE_TAG, version, settlementChainId, settlementEez,
   * eezRollupId, rollup0ChainId))}.
   *
   * @param version the envelope version
   * @param settlementChainId the settlement L1 chain id
   * @param settlementEez the canonical EEZ contract on the settlement L1 (not {@code EEZL2})
   * @param eezRollupId Rollup0's EEZ rollup id
   * @param rollup0ChainId the Rollup0 EVM chain id
   * @return the source domain
   */
  public static Bytes32 sourceDomain(
      final int version,
      final BigInteger settlementChainId,
      final Address settlementEez,
      final long eezRollupId,
      final BigInteger rollup0ChainId) {
    return Abi.keccak256(
        Abi.encode(
            DOMAIN_TYPE,
            List.of(
                Rollup0Constants.INBOUND_SOURCE_TAG,
                BigInteger.valueOf(version),
                settlementChainId,
                settlementEez,
                uint64(eezRollupId),
                rollup0ChainId)));
  }

  /**
   * {@code keccak256(abi.encode(sourceDomain, settlementTimestamp, settlementParentHash,
   * manifestIndex, crossChainCallHash))}.
   *
   * @param sourceDomain the deployment's source domain
   * @param settlement the authenticated settlement context
   * @param manifestIndex zero-based ordinal of the action's bracket in decoded message order
   * @param crossChainCallHash the action's EEZ call hash ({@link EezHashes#inboundCallHash})
   * @return the source hash
   */
  public static Bytes32 sourceHash(
      final Bytes32 sourceDomain,
      final SettlementContext settlement,
      final long manifestIndex,
      final Bytes32 crossChainCallHash) {
    return Abi.keccak256(
        Abi.encode(
            SOURCE_TYPE,
            List.of(
                sourceDomain,
                uint64(settlement.timestamp()),
                settlement.parentHash(),
                uint64(manifestIndex),
                crossChainCallHash)));
  }
}
