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

import org.hyperledger.besu.config.GenesisConfigOptions;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;

import java.math.BigInteger;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * The parameters of a rollupX chain, the profile the reference client (eez-rollupx) runs, read from
 * the {@code rollupX} section of the genesis {@code config}. Besu lower-cases genesis keys, so the
 * section is read case-insensitively.
 *
 * @param chainId the L2 EVM chain id (genesis {@code chainId})
 * @param feeCollector the account credited with base fees instead of burning them
 * @param blockIntervalSeconds seconds between L2 blocks
 * @param syncIntervalBlocks L2 blocks per settlement interval
 * @param historyUpdateOffset block position within an interval that imports L1 history
 * @param l1Reference the finalized settlement block the chain starts from
 */
public record RollupXChainConfig(
    BigInteger chainId,
    Address feeCollector,
    long blockIntervalSeconds,
    int syncIntervalBlocks,
    int historyUpdateOffset,
    L1Reference l1Reference) {

  /** The genesis config section this profile reads. */
  public static final String SECTION = "rollupX";

  /** The L2 execution manager, a fixed genesis predeploy. */
  public static final Address EEZ_L2_ADDRESS =
      Address.fromHexString("0x4B2760F84B83d6b3829E30A7573B0172B6d6402C");

  /** Fixed block gas limit. */
  public static final long GAS_LIMIT = 30_000_000L;

  /** Fixed genesis base fee, 1 gwei. */
  public static final Wei GENESIS_BASE_FEE = Wei.of(1_000_000_000L);

  /** Base fee elasticity 2 (Besu's default) and change denominator 50. */
  public static final long BASE_FEE_MAX_CHANGE_DENOMINATOR = 50L;

  /** A 0x45 transaction gets the block's remaining gas, capped by EIP-7825. */
  public static final long SYSTEM_TX_GAS_CAP = 16_777_216L;

  /** {@code EEZL2.loadExecutionTable}: opens a group the next ordinary transaction closes. */
  public static final Bytes LOAD_EXECUTION_TABLE_SELECTOR = Bytes.fromHexString("0xbf2eebd3");

  /** {@code EEZL2.executeIncomingCrossChainCall}: a group of its own. */
  public static final Bytes EXECUTE_INCOMING_SELECTOR = Bytes.fromHexString("0xc3fb5f3d");

  /**
   * The settlement block a rollupX chain starts from.
   *
   * @param chainId the settlement chain id
   * @param number the block number
   * @param hash the block hash
   * @param timestamp the block timestamp
   * @param prevRandao the block's {@code prevRandao}, which seeds L2 randomness until the first
   *     history import
   */
  public record L1Reference(
      BigInteger chainId, long number, Hash hash, long timestamp, Bytes32 prevRandao) {}

  /**
   * Reads the configuration, or returns empty when the genesis has no {@code rollupX} section.
   *
   * @param genesis the genesis config options
   * @return the configuration, if this genesis defines a rollupX chain
   * @throws IllegalArgumentException if the section is present but incomplete or malformed
   */
  public static Optional<RollupXChainConfig> fromGenesis(final GenesisConfigOptions genesis) {
    return genesis.getCustomSection(SECTION).map(section -> parse(genesis, section));
  }

  @SuppressWarnings("unchecked")
  private static RollupXChainConfig parse(
      final GenesisConfigOptions genesis, final Map<String, Object> section) {
    final BigInteger chainId =
        genesis
            .getChainId()
            .orElseThrow(
                () -> new IllegalArgumentException("A rollupX genesis requires a chainId"));
    if (genesis.getTerminalTotalDifficulty().map(ttd -> !ttd.isZero()).orElse(true)) {
      throw new IllegalArgumentException(
          "A rollupX genesis must be post-merge from genesis (terminalTotalDifficulty 0)");
    }
    if (genesis.getOsakaTime().orElse(-1L) != 0L) {
      throw new IllegalArgumentException("A rollupX genesis must activate Osaka at timestamp 0");
    }
    if (!genesis.getBaseFeePerGas().map(GENESIS_BASE_FEE::equals).orElse(true)) {
      throw new IllegalArgumentException("A rollupX genesis base fee must be 1 gwei");
    }
    final Object reference =
        value(section, "l1Reference").orElseThrow(() -> missing("l1Reference"));
    if (!(reference instanceof Map<?, ?>)) {
      throw new IllegalArgumentException("The genesis rollupX l1Reference is not an object");
    }
    final Map<String, Object> l1 = (Map<String, Object>) reference;
    return new RollupXChainConfig(
        chainId,
        string(section, "feeCollector")
            .map(Address::fromHexString)
            .orElseThrow(() -> missing("feeCollector")),
        number(section, "blockIntervalSeconds").longValueExact(),
        number(section, "syncIntervalBlocks").intValueExact(),
        number(section, "historyUpdateOffset").intValueExact(),
        new L1Reference(
            number(l1, "chainId"),
            number(l1, "number").longValueExact(),
            Hash.fromHexString(string(l1, "hash").orElseThrow(() -> missing("l1Reference.hash"))),
            number(l1, "timestamp").longValueExact(),
            Bytes32.fromHexString(
                string(l1, "prevRandao").orElseThrow(() -> missing("l1Reference.prevRandao")))));
  }

  private static IllegalArgumentException missing(final String key) {
    return new IllegalArgumentException("The genesis rollupX section is missing " + key);
  }

  private static Optional<Object> value(final Map<String, Object> section, final String key) {
    return Optional.ofNullable(section.get(key.toLowerCase(Locale.ROOT)));
  }

  private static Optional<String> string(final Map<String, Object> section, final String key) {
    return value(section, key).map(Object::toString);
  }

  private static BigInteger number(final Map<String, Object> section, final String key) {
    final Object v = value(section, key).orElseThrow(() -> missing(key));
    return switch (v) {
      case Number n -> new BigInteger(n.toString());
      case String s when s.startsWith("0x") -> new BigInteger(s.substring(2), 16);
      case String s -> new BigInteger(s);
      default ->
          throw new IllegalArgumentException(
              "The genesis rollupX field " + key + " is not a number: " + v);
    };
  }
}
