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
package eez.besu.follower.protocol.codec;

import static java.util.Objects.requireNonNull;

import org.hyperledger.besu.datatypes.Address;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * One Rollup0 action-manifest entry: an EEZ bracket {@code Initiate(0, 0x) / Call(rollup0, ...) /
 * ReturnSuccess|ReturnFail / Finish} (Rollup0 D.4.1, G.6). The call's source chain is always L1
 * ({@code 0}) and its target chain is always the Rollup0 EEZ rollup id.
 *
 * @param manifestIndex the bracket ordinal in decoded message order, from zero
 * @param fromAddress the L1 source address
 * @param toAddress the Rollup0 target address
 * @param value the call value in wei
 * @param gas the call's gas, an unsigned 64-bit simulation input (not part of the call hash)
 * @param data the exact calldata
 * @param success true for {@code ReturnSuccess}, false for the terminal {@code ReturnFail}
 * @param returnData the exact return data, or revert data when {@code success} is false
 */
public record ActionBracket(
    int manifestIndex,
    Address fromAddress,
    Address toAddress,
    UInt256 value,
    long gas,
    Bytes data,
    boolean success,
    Bytes returnData) {

  public ActionBracket {
    requireNonNull(fromAddress);
    requireNonNull(toAddress);
    requireNonNull(value);
    requireNonNull(data);
    requireNonNull(returnData);
  }
}
