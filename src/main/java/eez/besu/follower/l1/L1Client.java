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

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.VersionedHash;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/** A minimal JSON-RPC client for the settlement chain's execution layer. */
public class L1Client {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private final URI endpoint;
  private final HttpClient http;
  private final AtomicLong ids = new AtomicLong();

  public L1Client(final URI endpoint) {
    this.endpoint = endpoint;
    this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  }

  /** Returns the chain id. */
  public long chainId() {
    return Long.decode(call("eth_chainId").asText());
  }

  /**
   * Returns a block by tag ({@code latest}, {@code safe}, {@code finalized}).
   *
   * @param tag the tag
   * @return the block
   */
  public L1Block block(final String tag) {
    return parseBlock(call("eth_getBlockByNumber", tag, false));
  }

  /**
   * Returns a canonical block by number.
   *
   * @param number the block number
   * @return the block, or empty if the chain is not that long
   */
  public Optional<L1Block> block(final long number) {
    final JsonNode block = call("eth_getBlockByNumber", quantity(number), false);
    return block.isNull() ? Optional.empty() : Optional.of(parseBlock(block));
  }

  /**
   * Returns the logs of a contract in a block range, in canonical order.
   *
   * @param address the contract
   * @param fromBlock the first block, inclusive
   * @param toBlock the last block, inclusive
   * @return the logs
   */
  public List<L1Log> logs(final Address address, final long fromBlock, final long toBlock) {
    final JsonNode logs =
        call(
            "eth_getLogs",
            Map.of(
                "address", address.toHexString(),
                "fromBlock", quantity(fromBlock),
                "toBlock", quantity(toBlock)));
    final List<L1Log> result = new ArrayList<>();
    for (final JsonNode log : logs) {
      final List<Bytes32> topics = new ArrayList<>();
      log.get("topics").forEach(t -> topics.add(Bytes32.fromHexString(t.asText())));
      result.add(
          new L1Log(
              Address.fromHexString(log.get("address").asText()),
              topics,
              Bytes.fromHexString(log.get("data").asText()),
              Long.decode(log.get("blockNumber").asText()),
              Hash.fromHexString(log.get("blockHash").asText()),
              Hash.fromHexString(log.get("transactionHash").asText()),
              Integer.decode(log.get("transactionIndex").asText()),
              Integer.decode(log.get("logIndex").asText())));
    }
    // RPC response order is not consensus order (draft 10.1)
    result.sort(
        (a, b) ->
            a.blockNumber() != b.blockNumber()
                ? Long.compare(a.blockNumber(), b.blockNumber())
                : Integer.compare(a.logIndex(), b.logIndex()));
    return result;
  }

  /**
   * Returns a transaction with its receipt status.
   *
   * @param hash the transaction hash
   * @return the transaction
   */
  public L1Transaction transaction(final Hash hash) {
    final JsonNode tx = call("eth_getTransactionByHash", hash.toHexString());
    final JsonNode receipt = call("eth_getTransactionReceipt", hash.toHexString());
    if (tx.isNull() || receipt.isNull()) {
      throw new L1Exception("Settlement transaction " + hash + " is not available");
    }
    final List<VersionedHash> versionedHashes = new ArrayList<>();
    tx.path("blobVersionedHashes")
        .forEach(h -> versionedHashes.add(new VersionedHash(Bytes32.fromHexString(h.asText()))));
    return new L1Transaction(
        hash,
        Hash.fromHexString(tx.get("blockHash").asText()),
        Integer.decode(tx.get("transactionIndex").asText()),
        Address.fromHexString(tx.get("from").asText()),
        tx.hasNonNull("to")
            ? Optional.of(Address.fromHexString(tx.get("to").asText()))
            : Optional.empty(),
        Bytes.fromHexString(tx.get("input").asText()),
        versionedHashes,
        "0x1".equals(receipt.path("status").asText()));
  }

  private static L1Block parseBlock(final JsonNode block) {
    if (block == null || block.isNull()) {
      throw new L1Exception("Settlement block not available");
    }
    return new L1Block(
        Long.decode(block.get("number").asText()),
        Hash.fromHexString(block.get("hash").asText()),
        Hash.fromHexString(block.get("parentHash").asText()),
        Long.decode(block.get("timestamp").asText()),
        Bytes32.fromHexString(block.get("mixHash").asText()));
  }

  private JsonNode call(final String method, final Object... params) {
    try {
      final String body =
          MAPPER.writeValueAsString(
              Map.of(
                  "jsonrpc",
                  "2.0",
                  "id",
                  ids.incrementAndGet(),
                  "method",
                  method,
                  "params",
                  params));
      final HttpResponse<String> response =
          http.send(
              HttpRequest.newBuilder(endpoint)
                  .timeout(TIMEOUT)
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(body))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      final JsonNode json = MAPPER.readTree(response.body());
      if (json.has("error")) {
        throw new L1Exception(method + " failed: " + json.get("error"));
      }
      return json.get("result");
    } catch (final IOException e) {
      throw new L1Exception(method + " failed: " + e.getMessage(), e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new L1Exception(method + " interrupted", e);
    }
  }

  static String quantity(final long value) {
    return "0x" + Long.toHexString(value);
  }
}
