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
package eez.besu.follower.chain;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.mainnet.feemarket.BlobFeeMarket;

import java.util.Optional;

/**
 * The Rollup0 fee market (draft chapter 11): EIP-1559 with elasticity 2 and a base fee change
 * denominator of 50, and base and blob fees credited to {@code FEE_COLLECTOR} rather than burned.
 */
public class EezFeeMarket extends BlobFeeMarket {

  private final long baseFeeMaxChangeDenominator;
  private final Address feeCollector;

  public EezFeeMarket(
      final Optional<Wei> genesisBaseFee,
      final long baseFeeUpdateFraction,
      final long baseFeeMaxChangeDenominator,
      final Address feeCollector) {
    super(0L, genesisBaseFee, baseFeeUpdateFraction);
    this.baseFeeMaxChangeDenominator = baseFeeMaxChangeDenominator;
    this.feeCollector = feeCollector;
  }

  @Override
  public long getBasefeeMaxChangeDenominator() {
    return baseFeeMaxChangeDenominator;
  }

  @Override
  public Optional<Address> getBaseFeeCollector() {
    return Optional.of(feeCollector);
  }
}
