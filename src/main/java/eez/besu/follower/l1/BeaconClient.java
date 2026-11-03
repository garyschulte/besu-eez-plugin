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
package eez.besu.follower.l1;

import org.hyperledger.besu.datatypes.VersionedHash;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ethereum.ckzg4844.CKZG4844JNI;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Fetches blobs from a settlement-chain beacon node and authenticates each one against the
 * versioned hash the settlement transaction committed to, by recomputing its KZG commitment. A blob
 * that does not hash to the requested versioned hash is rejected, so the beacon node is not trusted
 * (draft 10.2 step 3).
 */
public class BeaconClient {

  /** Bytes per blob: 4096 field elements of 32 bytes. */
  public static final int BLOB_SIZE = 131_072;

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final long SECONDS_PER_SLOT = 12L;

  private final URI endpoint;
  private final HttpClient http;
  private volatile Long genesisTime;

  public BeaconClient(final URI endpoint) {
    this.endpoint = endpoint;
    this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  }

  /**
   * Returns the blobs with these versioned hashes from the beacon block at the slot of a settlement
   * block, in the order requested.
   *
   * @param blockTimestamp the settlement execution block's timestamp
   * @param versionedHashes the versioned hashes, in the order wanted
   * @return the authenticated blobs
   * @throws L1Exception if a blob is unavailable or does not match its versioned hash
   */
  public List<Bytes> blobs(final long blockTimestamp, final List<VersionedHash> versionedHashes) {
    if (versionedHashes.isEmpty()) {
      return List.of();
    }
    final long slot = (blockTimestamp - genesisTime()) / SECONDS_PER_SLOT;
    final Map<VersionedHash, Bytes> byHash = new HashMap<>();
    for (final Bytes blob : fetch(slot, versionedHashes)) {
      byHash.put(versionedHash(blob), blob);
    }
    return versionedHashes.stream()
        .map(
            vh -> {
              final Bytes blob = byHash.get(vh);
              if (blob == null) {
                throw new L1Exception("Blob " + vh + " is not available at slot " + slot);
              }
              return blob;
            })
        .toList();
  }

  /**
   * Computes a blob's EIP-4844 versioned hash: {@code 0x01 || sha256(commitment)[1:]}.
   *
   * @param blob the blob
   * @return its versioned hash
   */
  public static VersionedHash versionedHash(final Bytes blob) {
    if (blob.size() != BLOB_SIZE) {
      throw new L1Exception("Blob has " + blob.size() + " bytes, not " + BLOB_SIZE);
    }
    final byte[] commitment = CKZG4844JNI.blobToKzgCommitment(blob.toArrayUnsafe());
    try {
      final byte[] hash = MessageDigest.getInstance("SHA-256").digest(commitment);
      hash[0] = VersionedHash.SHA256_VERSION_ID;
      return new VersionedHash(Bytes32.wrap(hash));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private List<Bytes> fetch(final long slot, final List<VersionedHash> versionedHashes) {
    final String query =
        versionedHashes.stream()
            .map(
                vh ->
                    "versioned_hashes="
                        + URLEncoder.encode(vh.getBytes().toHexString(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
    try {
      // the post-Fulu endpoint, returning only the blobs asked for
      final JsonNode data = get("/eth/v1/beacon/blobs/" + slot + "?" + query).get("data");
      return parseBlobs(data, false);
    } catch (final L1Exception notSupported) {
      // older beacon nodes serve full sidecars
      final JsonNode data = get("/eth/v1/beacon/blob_sidecars/" + slot).get("data");
      return parseBlobs(data, true);
    }
  }

  private static List<Bytes> parseBlobs(final JsonNode data, final boolean sidecars) {
    if (data == null || !data.isArray()) {
      throw new L1Exception("Beacon node returned no blobs");
    }
    final java.util.ArrayList<Bytes> blobs = new java.util.ArrayList<>();
    data.forEach(
        node -> blobs.add(Bytes.fromHexString((sidecars ? node.get("blob") : node).asText())));
    return blobs;
  }

  private long genesisTime() {
    if (genesisTime == null) {
      genesisTime =
          Long.parseLong(get("/eth/v1/beacon/genesis").get("data").get("genesis_time").asText());
    }
    return genesisTime;
  }

  private JsonNode get(final String path) {
    try {
      final HttpResponse<String> response =
          http.send(
              HttpRequest.newBuilder(endpoint.resolve(path))
                  .timeout(TIMEOUT)
                  .header("Accept", "application/json")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new L1Exception("GET " + path + " returned " + response.statusCode());
      }
      return MAPPER.readTree(response.body());
    } catch (final IOException e) {
      throw new L1Exception("GET " + path + " failed: " + e.getMessage(), e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new L1Exception("GET " + path + " interrupted", e);
    }
  }
}
