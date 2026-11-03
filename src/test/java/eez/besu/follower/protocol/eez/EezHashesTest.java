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

import static eez.besu.follower.protocol.eez.CoreVectors.DATA;
import static eez.besu.follower.protocol.eez.CoreVectors.H0;
import static eez.besu.follower.protocol.eez.CoreVectors.H1;
import static eez.besu.follower.protocol.eez.CoreVectors.H_PARENT;
import static eez.besu.follower.protocol.eez.CoreVectors.ONE_ETHER;
import static eez.besu.follower.protocol.eez.CoreVectors.ROLLUP_ID;
import static eez.besu.follower.protocol.eez.CoreVectors.SOURCE;
import static eez.besu.follower.protocol.eez.CoreVectors.TARGET;
import static eez.besu.follower.protocol.eez.CoreVectors.bytes32;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;

class EezHashesTest {

  @Test
  void inboundCallHashMatchesEez() {
    assertThat(EezHashes.inboundCallHash(SOURCE, TARGET, ROLLUP_ID, ONE_ETHER, DATA))
        .isEqualTo(bytes32("callHash"));
    assertThat(EezHashes.inboundCallHash(SOURCE, TARGET, ROLLUP_ID, BigInteger.ZERO, Bytes.EMPTY))
        .isEqualTo(bytes32("callHash2"));
  }

  @Test
  void callHashCommitsToGasAndStaticFlag() {
    final Bytes32 base =
        EezHashes.crossChainCallHash(false, SOURCE, 0, TARGET, ROLLUP_ID, ONE_ETHER, 0, DATA);
    assertThat(base).isEqualTo(bytes32("callHash"));
    assertThat(
            EezHashes.crossChainCallHash(false, SOURCE, 0, TARGET, ROLLUP_ID, ONE_ETHER, 1, DATA))
        .isNotEqualTo(base);
    assertThat(EezHashes.crossChainCallHash(true, SOURCE, 0, TARGET, ROLLUP_ID, ONE_ETHER, 0, DATA))
        .isNotEqualTo(base);
  }

  @Test
  void l1EntrySeedsMatchEez() {
    final RollupUpdate anchor = new RollupUpdate(ROLLUP_ID, BigInteger.ZERO, H_PARENT, H0);
    assertThat(EezHashes.l1EntrySeed(List.of(anchor), Bytes32.ZERO))
        .isEqualTo(bytes32("anchorRollingHash"));
    final RollupUpdate inbound = new RollupUpdate(ROLLUP_ID, ONE_ETHER, H0, H1);
    assertThat(EezHashes.l1EntrySeed(List.of(inbound), bytes32("callHash")))
        .isEqualTo(bytes32("inboundL1RollingHash"));
  }

  @Test
  void l2RollingHashMatchesExecutedEntry() {
    final Bytes32 callHash = bytes32("callHash");
    final Bytes32 rolling =
        EezHashes.callEnd(
            EezHashes.callBegin(EezHashes.l2EntrySeed(callHash), callHash),
            true,
            CoreVectors.RETURN_DATA);
    assertThat(rolling).isEqualTo(bytes32("l2RollingHash"));
  }

  @Test
  void nestedFoldsUseTheirTags() {
    final Bytes32 previous = Bytes32.ZERO;
    final Bytes32 call = bytes32("callHash");
    assertThat(EezHashes.nestedBegin(previous, call))
        .isEqualTo(
            org.hyperledger.besu.crypto.Hash.keccak256(
                Bytes.concatenate(previous, Bytes.of(3), call)));
    assertThat(EezHashes.nestedEnd(previous))
        .isEqualTo(
            org.hyperledger.besu.crypto.Hash.keccak256(Bytes.concatenate(previous, Bytes.of(4))));
  }
}
