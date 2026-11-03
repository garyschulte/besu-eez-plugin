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

import java.math.BigInteger;
import java.util.List;
import java.util.function.Function;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/** Conversions between decoded ABI values and the record fields of the EEZ structs. */
final class AbiValues {

  private AbiValues() {}

  /** Unsigned 64-bit Java long as an ABI integer. */
  static BigInteger uint64(final long value) {
    return new BigInteger(Long.toUnsignedString(value));
  }

  /** Decoded {@code uint64} (or narrower) as an unsigned-semantics long. */
  static long toU64(final Object value) {
    return ((BigInteger) value).longValue();
  }

  static int toU16(final Object value) {
    return ((BigInteger) value).intValueExact();
  }

  static BigInteger integer(final Object value) {
    return (BigInteger) value;
  }

  static Bytes32 bytes32(final Object value) {
    return (Bytes32) value;
  }

  static Bytes bytes(final Object value) {
    return (Bytes) value;
  }

  static Address address(final Object value) {
    return (Address) value;
  }

  static boolean bool(final Object value) {
    return (Boolean) value;
  }

  static List<?> list(final Object value) {
    return (List<?>) value;
  }

  static <T> List<T> listOf(final Object value, final Function<List<?>, T> fromAbi) {
    return list(value).stream().map(item -> fromAbi.apply(list(item))).toList();
  }

  static List<Long> u64List(final Object value) {
    return list(value).stream().map(AbiValues::toU64).toList();
  }

  static <T> List<Object> toAbi(final List<T> items, final Function<T, List<Object>> toAbi) {
    return items.stream().<Object>map(toAbi).toList();
  }
}
