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

import eez.besu.follower.protocol.eez.abi.Abi;

import org.hyperledger.besu.datatypes.Address;

import java.nio.charset.StandardCharsets;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/** Fixed Rollup0 protocol values from the draft spec (rev 2c6d346). */
public final class Rollup0Constants {

  /** Implicit sender and {@code EEZL2} system caller (ch. 3, A.3). */
  public static final Address SYSTEM_ADDRESS =
      Address.fromHexString("0xfffffffffffffffffffffffffffffffffffffffe");

  /** Default {@code EEZL2} predeploy address (ch. 3, A.3). */
  public static final Address DEFAULT_EEZ_L2_ADDRESS =
      Address.fromHexString("0xee50000000000000000000000000000000000000");

  /** EIP-2718 type byte of the inbound protocol transaction (F.3). */
  public static final int INBOUND_TX_TYPE = 0x45;

  /** Type-0x45 envelope version, also the {@code sourceDomain} version (F.3). */
  public static final int INBOUND_ENVELOPE_VERSION = 0;

  /** Envelope-version-0 gas cap for an inbound protocol transaction (D.5, F.3). */
  public static final long INBOUND_GAS_CAP = 16_777_216L;

  /** EEZ network id of the settlement L1 ({@code MAINNET_ROLLUP_ID}). */
  public static final long SETTLEMENT_L1_ROLLUP_ID = 0L;

  /** {@code batch.blockNumber} sentinel selecting the latest L1 context, {@code 2^64 - 1} (D.2). */
  public static final long LATEST_CONTEXT_BLOCK_NUMBER = -1L;

  /** {@code keccak256("EEZ_ROLLUP0_INBOUND_SOURCE")} (F.3). */
  public static final Bytes32 INBOUND_SOURCE_TAG = tag("EEZ_ROLLUP0_INBOUND_SOURCE");

  /** {@code keccak256("EEZ_ROLLUP0_CANDIDATE_V1")} (D.2). */
  public static final Bytes32 CANDIDATE_DOMAIN_TAG = tag("EEZ_ROLLUP0_CANDIDATE_V1");

  /** {@code keccak256("EEZ_ROLLUP0_UNSAFE_BLOCK_V1")} (D.6). */
  public static final Bytes32 UNSAFE_BLOCK_DOMAIN_TAG = tag("EEZ_ROLLUP0_UNSAFE_BLOCK_V1");

  private Rollup0Constants() {}

  private static Bytes32 tag(final String domain) {
    return Abi.keccak256(Bytes.wrap(domain.getBytes(StandardCharsets.US_ASCII)));
  }
}
