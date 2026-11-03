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

import org.hyperledger.besu.datatypes.Address;

import java.math.BigInteger;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * One successful Ethereum-to-Rollup0 action, taken from a manifest bracket {@code
 * InitiateCrossChainTransaction(0, 0x) · Call(rollup0Id, from, to, value, gas, data) ·
 * ReturnSuccess(returnData) · FinishCrossChainTransaction} (D.4.1, G.6). The source network is
 * always the settlement L1 (EEZ id 0).
 *
 * @param manifestIndex zero-based bracket ordinal in decoded message order
 * @param sourceAddress the bracket's {@code from_address}: the L1 caller of the Rollup0 proxy
 * @param targetAddress the bracket's {@code to_address}: the Rollup0 destination
 * @param value the bracket's {@code value} in wei
 * @param gas the bracket's {@code gas}; carried into {@code incomingCalls[0].gas}, never the 0x45
 *     gas limit
 * @param data the bracket's calldata
 * @param returnData the {@code ReturnSuccess} payload
 */
public record InboundAction(
    long manifestIndex,
    Address sourceAddress,
    Address targetAddress,
    BigInteger value,
    long gas,
    Bytes data,
    Bytes returnData) {

  /**
   * The L1 call hash of this action ({@link EezHashes#inboundCallHash}).
   *
   * @param rollup0EezRollupId Rollup0's EEZ rollup id
   * @return the call hash
   */
  public Bytes32 callHash(final long rollup0EezRollupId) {
    return EezHashes.inboundCallHash(sourceAddress, targetAddress, rollup0EezRollupId, value, data);
  }
}
