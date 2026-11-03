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

import static eez.besu.follower.protocol.eez.AbiValues.bytes32;
import static eez.besu.follower.protocol.eez.AbiValues.toU64;
import static eez.besu.follower.protocol.eez.AbiValues.uint64;

import eez.besu.follower.protocol.eez.abi.AbiType;

import java.util.List;

import org.apache.tuweni.bytes.Bytes32;

/**
 * A pin on a rollup's live root ({@code ExpectedRootPerRollup}).
 *
 * @param rollupId {@code uint64} rollup id
 * @param root the expected live root
 */
public record ExpectedRoot(long rollupId, Bytes32 root) {

  /** {@code (uint64,bytes32)}. */
  public static final AbiType.Tuple TYPE = AbiType.parseTuple("(uint64,bytes32)");

  /** Returns the ABI value of this struct. */
  public List<Object> toAbi() {
    return List.of(uint64(rollupId), root);
  }

  /**
   * Builds the struct from its decoded ABI value.
   *
   * @param v the decoded tuple
   * @return the struct
   */
  public static ExpectedRoot fromAbi(final List<?> v) {
    return new ExpectedRoot(toU64(v.get(0)), bytes32(v.get(1)));
  }
}
