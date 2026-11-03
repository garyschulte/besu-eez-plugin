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

import java.util.List;

/**
 * The ordered Rollup0 blocks a candidate anchors, decoded from {@code ChainOperation.operations}.
 * The last block is the terminal Sync block that the candidate's action brackets extend.
 *
 * @param blocks the blocks in order; never empty when produced by the decoder
 */
public record NativeBlockSpan(List<NativeBlock> blocks) {

  public NativeBlockSpan {
    blocks = List.copyOf(blocks);
  }

  public int blockCount() {
    return blocks.size();
  }

  /** The total number of pure-L2 transactions across the span. */
  public int transactionCount() {
    return blocks.stream().mapToInt(block -> block.transactions().size()).sum();
  }
}
