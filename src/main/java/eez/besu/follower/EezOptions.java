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
package eez.besu.follower;

import java.net.URI;
import java.time.Duration;

import picocli.CommandLine;

/** Command-line options, registered under the {@code eez} namespace. */
public class EezOptions {

  @CommandLine.Option(
      names = {"--plugin-eez-l1-rpc-url"},
      paramLabel = "<URL>",
      description = "JSON-RPC endpoint of the settlement chain's execution client")
  URI l1RpcUrl;

  @CommandLine.Option(
      names = {"--plugin-eez-l1-beacon-url"},
      paramLabel = "<URL>",
      description = "REST endpoint of the settlement chain's beacon node, for blobs")
  URI l1BeaconUrl;

  @CommandLine.Option(
      names = {"--plugin-eez-poll-interval"},
      paramLabel = "<SECONDS>",
      description = "How often to poll the settlement chain (default: ${DEFAULT-VALUE})")
  long pollIntervalSeconds = 6;

  @CommandLine.Option(
      names = {"--plugin-eez-follower-enabled"},
      paramLabel = "<BOOLEAN>",
      arity = "1",
      description =
          "Derive the chain from the settlement chain. When false, only the Rollup0 execution"
              + " rules are applied (default: ${DEFAULT-VALUE})")
  boolean followerEnabled = true;

  Duration pollInterval() {
    return Duration.ofSeconds(pollIntervalSeconds);
  }
}
