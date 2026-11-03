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

import java.util.List;

import org.apache.tuweni.bytes.Bytes;

/**
 * The Rollup0-relevant content of a candidate protocol V1 stream (Rollup0 D.4.1).
 *
 * @param operations the raw {@code ChainOperation.operations} bytes; decode them with {@link
 *     NativeBlockSpanV0Codec}
 * @param manifest the action brackets in manifest order; at most the last one failed
 */
public record Rollup0Candidate(Bytes operations, List<ActionBracket> manifest) {

  public Rollup0Candidate {
    requireNonNull(operations);
    manifest = List.copyOf(manifest);
  }

  /** The number of expected-success actions, {@code s} in D.3. */
  public int successCount() {
    return manifest.isEmpty() || manifest.getLast().success()
        ? manifest.size()
        : manifest.size() - 1;
  }
}
