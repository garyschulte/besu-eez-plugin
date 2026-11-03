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

import java.math.BigInteger;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The Rollup0 parameters a follower needs beyond standard genesis fields, read from the {@code eez}
 * section of the genesis {@code config}. Besu lower-cases genesis keys, so the section is read
 * case-insensitively; the camel-case names below are what a genesis file should use.
 *
 * @param chainId the Rollup0 EVM chain id (genesis {@code chainId})
 * @param rollupId the Rollup0 rollup id assigned by the settlement EEZ contract
 * @param settlementChainId the chain id of the settlement chain (Ethereum)
 * @param settlementEezAddress the EEZ contract on the settlement chain
 * @param managerAddress the Rollup0 manager registered with EEZ (draft D.2)
 * @param settlementWrapperAddress the active settlement wrapper (draft D.2.1)
 * @param settlementWrapperFunction the wrapper function that takes the EEZ batch; the draft does
 *     not fix its name
 * @param eezL2Address the {@code EEZL2} predeploy on Rollup0
 * @param feeCollector the account credited with base fees instead of burning them
 * @param settlementStartBlock the first settlement block to scan for this rollup's events
 * @param genesisReferenceBlockHash the finalized settlement block whose {@code prevRandao} seeds
 *     the genesis interval (draft 4.2)
 * @param blockInterval seconds between Rollup0 blocks
 * @param positionsPerInterval Rollup0 blocks per settlement interval; the last is the Sync block
 * @param baseFeeMaxChangeDenominator EIP-1559 base fee change denominator
 */
public record EezChainConfig(
    BigInteger chainId,
    BigInteger rollupId,
    BigInteger settlementChainId,
    Address settlementEezAddress,
    Address managerAddress,
    Address settlementWrapperAddress,
    String settlementWrapperFunction,
    Address eezL2Address,
    Address feeCollector,
    long settlementStartBlock,
    Optional<Hash> genesisReferenceBlockHash,
    long blockInterval,
    int positionsPerInterval,
    long baseFeeMaxChangeDenominator) {

  /** The genesis config section this plugin reads. */
  public static final String SECTION = "eez";

  /** Draft Appendix A.3. */
  public static final Address DEFAULT_EEZ_L2_ADDRESS =
      Address.fromHexString("0xee50000000000000000000000000000000000000");

  /** An assumption: the draft names the wrapper entry point {@code submitCandidate} but no ABI. */
  public static final String DEFAULT_SETTLEMENT_WRAPPER_FUNCTION = "submitCandidate";

  /** Draft chapter 11: elasticity 2, denominator 50. */
  public static final long DEFAULT_BASE_FEE_MAX_CHANGE_DENOMINATOR = 50L;

  /** Draft chapter 4.1: 2 second blocks, six per 12 second settlement interval. */
  public static final long DEFAULT_BLOCK_INTERVAL = 2L;

  public static final int DEFAULT_POSITIONS_PER_INTERVAL = 6;

  /** Draft chapter 4.2. */
  public static final long GAS_LIMIT = 30_000_000L;

  /**
   * Reads the configuration, or returns empty when the genesis has no {@code eez} section, which
   * means the chain is not a Rollup0 chain.
   *
   * @param genesis the genesis config options
   * @return the configuration, if this genesis defines a Rollup0 chain
   * @throws IllegalArgumentException if the section is present but incomplete or malformed
   */
  public static Optional<EezChainConfig> fromGenesis(final GenesisConfigOptions genesis) {
    return genesis.getCustomSection(SECTION).map(section -> parse(genesis, section));
  }

  private static EezChainConfig parse(
      final GenesisConfigOptions genesis, final Map<String, Object> section) {
    final BigInteger chainId =
        genesis
            .getChainId()
            .orElseThrow(() -> new IllegalArgumentException("An EEZ genesis requires a chainId"));
    if (genesis.getTerminalTotalDifficulty().map(ttd -> !ttd.isZero()).orElse(true)) {
      throw new IllegalArgumentException(
          "An EEZ genesis must be post-merge from genesis (terminalTotalDifficulty 0)");
    }
    if (genesis.getOsakaTime().orElse(-1L) != 0L) {
      throw new IllegalArgumentException("An EEZ genesis must activate Osaka at timestamp 0");
    }
    return new EezChainConfig(
        chainId,
        bigInteger(section, "rollupId").orElseThrow(() -> missing("rollupId")),
        bigInteger(section, "settlementChainId").orElseThrow(() -> missing("settlementChainId")),
        address(section, "settlementEezAddress").orElseThrow(() -> missing("settlementEezAddress")),
        address(section, "managerAddress").orElseThrow(() -> missing("managerAddress")),
        address(section, "settlementWrapperAddress")
            .orElseThrow(() -> missing("settlementWrapperAddress")),
        string(section, "settlementWrapperFunction").orElse(DEFAULT_SETTLEMENT_WRAPPER_FUNCTION),
        address(section, "eezL2Address").orElse(DEFAULT_EEZ_L2_ADDRESS),
        address(section, "feeCollector").orElseThrow(() -> missing("feeCollector")),
        bigInteger(section, "settlementStartBlock").map(BigInteger::longValueExact).orElse(0L),
        string(section, "genesisReferenceBlockHash").map(Hash::fromHexString),
        bigInteger(section, "blockInterval")
            .map(BigInteger::longValueExact)
            .orElse(DEFAULT_BLOCK_INTERVAL),
        bigInteger(section, "positionsPerInterval")
            .map(BigInteger::intValueExact)
            .orElse(DEFAULT_POSITIONS_PER_INTERVAL),
        bigInteger(section, "baseFeeMaxChangeDenominator")
            .map(BigInteger::longValueExact)
            .orElse(DEFAULT_BASE_FEE_MAX_CHANGE_DENOMINATOR));
  }

  private static IllegalArgumentException missing(final String key) {
    return new IllegalArgumentException("The genesis eez section is missing " + key);
  }

  private static Optional<Object> value(final Map<String, Object> section, final String key) {
    return Optional.ofNullable(section.get(key.toLowerCase(Locale.ROOT)));
  }

  private static Optional<String> string(final Map<String, Object> section, final String key) {
    return value(section, key).map(Object::toString);
  }

  private static Optional<Address> address(final Map<String, Object> section, final String key) {
    return string(section, key).map(Address::fromHexString);
  }

  private static Optional<BigInteger> bigInteger(
      final Map<String, Object> section, final String key) {
    return value(section, key)
        .map(
            v ->
                switch (v) {
                  case Number n -> new BigInteger(n.toString());
                  case String s when s.startsWith("0x") -> new BigInteger(s.substring(2), 16);
                  case String s -> new BigInteger(s);
                  default ->
                      throw new IllegalArgumentException(
                          "The genesis eez field " + key + " is not a number: " + v);
                });
  }
}
