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

import org.hyperledger.besu.datatypes.Address;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Vectors produced by running the eez-core contracts at rev 84a5b52 in Foundry (see {@code
 * Rollup0Vectors.t.sol.txt} next to the properties file).
 */
final class CoreVectors {

  static final Address SOURCE = Address.fromHexString("0xc0ffee");
  static final Address TARGET = Address.fromHexString("0xdeadbeef");
  static final BigInteger ONE_ETHER = new BigInteger("1000000000000000000");
  static final Bytes DATA = Bytes.fromHexString("deadbeef");
  static final Bytes RETURN_DATA = Bytes.fromHexString("c0ffee");
  static final Bytes32 H_PARENT = word(0xaa);
  static final Bytes32 H0 = word(0xbb);
  static final Bytes32 H1 = word(0xcc);
  static final BigInteger ROLLUP0_CHAIN_ID = BigInteger.valueOf(424242);
  static final SettlementContext SETTLEMENT = new SettlementContext(1_700_000_012L, word(0x99));
  static final Bytes32 BLOB_HASH =
      Bytes32.fromHexString("0x01000000000000000000000000000000000000000000000000000000000000b1");

  private static final Properties PROPERTIES = load();

  static final long ROLLUP_ID = Long.parseLong(PROPERTIES.getProperty("rid"));
  static final Address EEZ = address("eez");
  static final Address MANAGER = address("manager");
  static final Address WRAPPER = address("wrapper");

  static final Rollup0Deployment DEPLOYMENT =
      new Rollup0Deployment(
          BigInteger.ONE,
          EEZ,
          MANAGER,
          WRAPPER,
          ROLLUP_ID,
          ROLLUP0_CHAIN_ID,
          Rollup0Constants.DEFAULT_EEZ_L2_ADDRESS);

  /** The action the Foundry run delivered at manifest index 0. */
  static final InboundAction ACTION =
      new InboundAction(0, SOURCE, TARGET, ONE_ETHER, 0, DATA, RETURN_DATA);

  private CoreVectors() {}

  /** A recorded log. */
  record Log(Address emitter, List<Bytes32> topics, Bytes data) {}

  static Bytes bytes(final String key) {
    return Bytes.fromHexString(require(key));
  }

  static Bytes32 bytes32(final String key) {
    return Bytes32.fromHexString(require(key));
  }

  static Address address(final String key) {
    return Address.fromHexString(require(key));
  }

  static Log log(final String section, final int index) {
    final String prefix = section + "." + index + ".";
    final List<Bytes32> topics = new ArrayList<>();
    for (int t = 0; PROPERTIES.containsKey(prefix + "topic" + t); t++) {
      topics.add(bytes32(prefix + "topic" + t));
    }
    return new Log(address(prefix + "emitter"), topics, bytes(prefix + "data"));
  }

  static Bytes32 word(final long value) {
    return Bytes32.leftPad(Bytes.ofUnsignedLong(value));
  }

  private static String require(final String key) {
    final String value = PROPERTIES.getProperty(key);
    if (value == null) {
      throw new IllegalArgumentException("Missing vector " + key);
    }
    return value;
  }

  private static Properties load() {
    try (InputStream in =
        CoreVectors.class.getResourceAsStream(
            "/protocol/eez/eez-core-84a5b52-vectors.properties")) {
      final Properties properties = new Properties();
      properties.load(in);
      return properties;
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
