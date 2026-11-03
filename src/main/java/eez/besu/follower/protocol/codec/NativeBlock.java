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

import java.util.List;

import org.apache.tuweni.bytes.Bytes;

/**
 * The non-derived inputs of one Rollup0 block in a V0 span (Rollup0 D.4.3).
 *
 * @param beneficiary the block's fee recipient; the zero address is valid
 * @param extraData the header {@code extraData}, 0 to 32 bytes
 * @param transactions the block's pure-L2 transactions as exact canonical EIP-2718 encodings, in
 *     order; not decoded or validated here
 */
public record NativeBlock(Address beneficiary, Bytes extraData, List<Bytes> transactions) {

  public static final int MAX_EXTRA_DATA_SIZE = 32;

  public NativeBlock {
    requireNonNull(beneficiary);
    requireNonNull(extraData);
    if (extraData.size() > MAX_EXTRA_DATA_SIZE) {
      throw new IllegalArgumentException("extraData exceeds 32 bytes: " + extraData.size());
    }
    transactions = List.copyOf(transactions);
  }
}
