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

import org.apache.tuweni.bytes.Bytes32;

/**
 * The identities a Rollup0 follower is configured with. None of these are fixed by the spec; they
 * come from genesis and the L1 deployment.
 *
 * @param settlementChainId the settlement L1 chain id
 * @param eez the canonical EEZ contract on the settlement L1
 * @param manager the Rollup0 manager registered with EEZ ({@code Rollup0Manager}, D.2)
 * @param settlementWrapper the active settlement wrapper ({@code Rollup0SettlementWrapper}, D.2)
 * @param eezRollupId Rollup0's {@code uint64} EEZ rollup id
 * @param rollup0ChainId the Rollup0 EVM chain id
 * @param eezL2 the {@code EEZL2} predeploy on Rollup0
 */
public record Rollup0Deployment(
    BigInteger settlementChainId,
    Address eez,
    Address manager,
    Address settlementWrapper,
    long eezRollupId,
    BigInteger rollup0ChainId,
    Address eezL2) {

  /**
   * Returns this deployment's envelope-version-0 {@code sourceDomain} (F.3).
   *
   * @return the source domain
   */
  public Bytes32 sourceDomain() {
    return InboundSource.sourceDomain(
        Rollup0Constants.INBOUND_ENVELOPE_VERSION,
        settlementChainId,
        eez,
        eezRollupId,
        rollup0ChainId);
  }
}
