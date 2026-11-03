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
package eez.besu.follower.protocol.eez.abi;

import org.hyperledger.besu.crypto.Hash;
import org.hyperledger.besu.datatypes.Address;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes;
import org.apache.tuweni.units.bigints.UInt256Value;

/**
 * Solidity ABI encoding ({@code abi.encode}) and decoding for the types in {@link AbiType}.
 *
 * <p>Java values: integers are {@link BigInteger} (encoding also accepts {@link Long}, {@link
 * Integer} and tuweni {@link UInt256Value}), {@code address} is {@link Address}, {@code bool} is
 * {@link Boolean}, {@code bytes<M>} and {@code bytes} are {@link Bytes}, arrays and tuples are
 * {@link List}s. Decoding returns {@link Bytes32} for {@code bytes32}.
 *
 * <p>Decoding follows the offsets in the input, as Solidity's decoder does, so non-canonical but
 * valid layouts decode. It rejects what Solidity's ABI coder v2 rejects: out-of-range offsets and
 * lengths, and words whose unused bits are not clean for the declared type.
 */
public final class Abi {

  private static final int WORD = 32;
  private static final BigInteger MAX_OFFSET = BigInteger.valueOf(Integer.MAX_VALUE);

  private Abi() {}

  /**
   * Returns {@code keccak256(data)}.
   *
   * @param data the preimage
   * @return the hash
   */
  public static Bytes32 keccak256(final Bytes data) {
    return Hash.keccak256(data);
  }

  /**
   * Returns the 4-byte function selector of a canonical signature such as {@code f(uint256)}.
   *
   * @param signature the canonical signature
   * @return the selector
   */
  public static Bytes selector(final String signature) {
    return keccak256(Bytes.wrap(signature.getBytes(StandardCharsets.US_ASCII))).slice(0, 4);
  }

  /**
   * Returns {@code abi.encode(values...)} for a parameter list.
   *
   * @param types the parameter types
   * @param values one value per parameter
   * @return the encoding
   */
  public static Bytes encode(final AbiType.Tuple types, final List<?> values) {
    return encodeTuple(types, values);
  }

  /**
   * Returns the ABI encoding of a single value as if it were the sole element of a parameter list,
   * i.e. {@code abi.encode(value)}.
   *
   * @param type the value's type
   * @param value the value
   * @return the encoding
   */
  public static Bytes encodeSingle(final AbiType type, final Object value) {
    return encodeTuple(new AbiType.Tuple(List.of(type)), List.of(value));
  }

  /**
   * Decodes {@code abi.encode(values...)} for a parameter list.
   *
   * @param types the parameter types
   * @param data the encoding; trailing bytes are ignored, as in Solidity
   * @return one decoded value per parameter
   * @throws AbiDecodingException if the encoding is malformed
   */
  public static List<Object> decode(final AbiType.Tuple types, final Bytes data) {
    return decodeTuple(types, data, 0);
  }

  /**
   * Decodes the encoding of a single value as if it were the sole element of a parameter list.
   *
   * @param type the value's type
   * @param data the encoding
   * @return the decoded value
   * @throws AbiDecodingException if the encoding is malformed
   */
  public static Object decodeSingle(final AbiType type, final Bytes data) {
    return decodeTuple(new AbiType.Tuple(List.of(type)), data, 0).getFirst();
  }

  /**
   * Decodes a single 32-byte word as a static type, as used for indexed event topics.
   *
   * @param type a static elementary type
   * @param word the 32-byte word
   * @return the decoded value
   */
  public static Object decodeWord(final AbiType type, final Bytes32 word) {
    if (type.isDynamic() || type instanceof AbiType.Tuple) {
      throw new IllegalArgumentException("Not a single-word type: " + type.canonical());
    }
    return decodeStatic(type, word, 0);
  }

  private static Bytes encodeTuple(final AbiType.Tuple types, final List<?> values) {
    final List<AbiType> components = types.components();
    if (values.size() != components.size()) {
      throw new IllegalArgumentException(
          "Tuple "
              + types.canonical()
              + " needs "
              + components.size()
              + " values, got "
              + values.size());
    }
    return encodeSequence(components, values);
  }

  private static Bytes encodeSequence(final List<AbiType> types, final List<?> values) {
    final List<Bytes> heads = new ArrayList<>(types.size());
    final List<Bytes> tails = new ArrayList<>();
    int headLength = 0;
    for (final AbiType type : types) {
      headLength += type.headSize();
    }
    int tailOffset = headLength;
    for (int i = 0; i < types.size(); i++) {
      final AbiType type = types.get(i);
      final Bytes encoded = encodeValue(type, values.get(i));
      if (type.isDynamic()) {
        heads.add(uintWord(BigInteger.valueOf(tailOffset)));
        tails.add(encoded);
        tailOffset += encoded.size();
      } else {
        heads.add(encoded);
      }
    }
    heads.addAll(tails);
    return Bytes.concatenate(heads);
  }

  private static Bytes encodeValue(final AbiType type, final Object value) {
    return switch (type) {
      case AbiType.Uint uint -> encodeUint(uint, value);
      case AbiType.Int signed -> encodeInt(signed, value);
      case AbiType.AddressType ignored -> Bytes32.leftPad(cast(Address.class, value).getBytes());
      case AbiType.Bool ignored ->
          uintWord(cast(Boolean.class, value) ? BigInteger.ONE : BigInteger.ZERO);
      case AbiType.FixedBytes fixed -> encodeFixedBytes(fixed, cast(Bytes.class, value));
      case AbiType.DynamicBytes ignored -> encodeDynamicBytes(cast(Bytes.class, value));
      case AbiType.Array array -> encodeArray(array, cast(List.class, value));
      case AbiType.Tuple tuple -> encodeTuple(tuple, cast(List.class, value));
    };
  }

  private static Bytes encodeUint(final AbiType.Uint type, final Object value) {
    final BigInteger integer = toBigInteger(value);
    if (integer.signum() < 0 || integer.bitLength() > type.bits()) {
      throw new IllegalArgumentException(integer + " does not fit " + type.canonical());
    }
    return uintWord(integer);
  }

  private static Bytes encodeInt(final AbiType.Int type, final Object value) {
    final BigInteger integer = toBigInteger(value);
    if (integer.bitLength() > type.bits() - 1) {
      throw new IllegalArgumentException(integer + " does not fit " + type.canonical());
    }
    final byte[] twos = integer.toByteArray();
    final MutableBytes word = MutableBytes.create(WORD);
    if (integer.signum() < 0) {
      word.fill((byte) 0xff);
    }
    word.set(WORD - twos.length, Bytes.wrap(twos));
    return word;
  }

  private static Bytes encodeFixedBytes(final AbiType.FixedBytes type, final Bytes value) {
    if (value.size() != type.size()) {
      throw new IllegalArgumentException(
          "Expected " + type.size() + " bytes for " + type.canonical() + ", got " + value.size());
    }
    return Bytes32.rightPad(value);
  }

  private static Bytes encodeDynamicBytes(final Bytes value) {
    return Bytes.concatenate(uintWord(BigInteger.valueOf(value.size())), padToWords(value));
  }

  private static Bytes encodeArray(final AbiType.Array type, final List<?> values) {
    final List<AbiType> types = new ArrayList<>(values.size());
    for (int i = 0; i < values.size(); i++) {
      types.add(type.element());
    }
    return Bytes.concatenate(
        uintWord(BigInteger.valueOf(values.size())), encodeSequence(types, values));
  }

  private static List<Object> decodeTuple(
      final AbiType.Tuple types, final Bytes data, final int start) {
    return decodeSequence(types.components(), data, start);
  }

  private static List<Object> decodeSequence(
      final List<AbiType> types, final Bytes data, final int start) {
    final List<Object> values = new ArrayList<>(types.size());
    int head = start;
    for (final AbiType type : types) {
      if (type.isDynamic()) {
        final int offset = readSize(data, head, "offset");
        values.add(decodeDynamic(type, data, checkedAdd(start, offset)));
      } else {
        values.add(decodeStatic(type, data, head));
      }
      head = checkedAdd(head, type.headSize());
    }
    return values;
  }

  private static Object decodeDynamic(final AbiType type, final Bytes data, final int position) {
    return switch (type) {
      case AbiType.DynamicBytes ignored -> {
        final int length = readSize(data, position, "bytes length");
        final int contentStart = checkedAdd(position, WORD);
        requireAvailable(data, contentStart, length);
        yield data.slice(contentStart, length).copy();
      }
      case AbiType.Array array -> {
        final int length = readSize(data, position, "array length");
        final int contentStart = checkedAdd(position, WORD);
        // every element occupies at least its head, so a length the data cannot hold is invalid
        requireAvailable(data, contentStart, (long) length * array.element().headSize());
        final List<AbiType> types = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
          types.add(array.element());
        }
        yield decodeSequence(types, data, contentStart);
      }
      case AbiType.Tuple tuple -> decodeTuple(tuple, data, position);
      default -> throw new IllegalStateException("Not dynamic: " + type.canonical());
    };
  }

  private static Object decodeStatic(final AbiType type, final Bytes data, final int position) {
    if (type instanceof AbiType.Tuple tuple) {
      return decodeTuple(tuple, data, position);
    }
    final Bytes32 word = readWord(data, position);
    return switch (type) {
      case AbiType.Uint uint -> {
        final BigInteger value = word.toUnsignedBigInteger();
        if (value.bitLength() > uint.bits()) {
          throw new AbiDecodingException(
              "Dirty high bits for " + uint.canonical() + " at " + position);
        }
        yield value;
      }
      case AbiType.Int signed -> {
        final BigInteger value = new BigInteger(word.toArrayUnsafe());
        if (value.bitLength() > signed.bits() - 1) {
          throw new AbiDecodingException(
              "Value out of range for " + signed.canonical() + " at " + position);
        }
        yield value;
      }
      case AbiType.AddressType ignored -> {
        if (!word.slice(0, 12).isZero()) {
          throw new AbiDecodingException("Dirty high bits for address at " + position);
        }
        yield Address.wrap(word.slice(12, 20).copy());
      }
      case AbiType.Bool ignored -> {
        final BigInteger value = word.toUnsignedBigInteger();
        if (value.compareTo(BigInteger.ONE) > 0) {
          throw new AbiDecodingException("Invalid bool at " + position);
        }
        yield value.equals(BigInteger.ONE);
      }
      case AbiType.FixedBytes fixed -> {
        if (!word.slice(fixed.size()).isZero()) {
          throw new AbiDecodingException(
              "Dirty low bits for " + fixed.canonical() + " at " + position);
        }
        yield fixed.size() == WORD ? word.copy() : word.slice(0, fixed.size()).copy();
      }
      default -> throw new IllegalStateException("Not static: " + type.canonical());
    };
  }

  private static Bytes32 readWord(final Bytes data, final int position) {
    requireAvailable(data, position, WORD);
    return Bytes32.wrap(data.slice(position, WORD));
  }

  private static int readSize(final Bytes data, final int position, final String what) {
    final BigInteger value = readWord(data, position).toUnsignedBigInteger();
    if (value.compareTo(MAX_OFFSET) > 0 || value.intValueExact() > data.size()) {
      throw new AbiDecodingException(what + " " + value + " at " + position + " exceeds the input");
    }
    return value.intValueExact();
  }

  private static void requireAvailable(final Bytes data, final int position, final long length) {
    if (position < 0 || position + length > data.size()) {
      throw new AbiDecodingException(
          "Need " + length + " bytes at " + position + ", input has " + data.size());
    }
  }

  private static int checkedAdd(final int a, final int b) {
    try {
      return Math.addExact(a, b);
    } catch (final ArithmeticException e) {
      throw new AbiDecodingException("Offset overflow");
    }
  }

  private static Bytes uintWord(final BigInteger value) {
    return Bytes32.leftPad(Bytes.wrap(value.toByteArray()).trimLeadingZeros());
  }

  private static Bytes padToWords(final Bytes value) {
    final int remainder = value.size() % WORD;
    return remainder == 0
        ? value
        : Bytes.concatenate(value, Bytes.wrap(new byte[WORD - remainder]));
  }

  private static BigInteger toBigInteger(final Object value) {
    return switch (value) {
      case BigInteger integer -> integer;
      case Long longValue -> BigInteger.valueOf(longValue);
      case Integer intValue -> BigInteger.valueOf(intValue);
      case UInt256Value<?> uint -> uint.toBigInteger();
      default -> throw new IllegalArgumentException("Not an ABI integer: " + value);
    };
  }

  private static <T> T cast(final Class<T> type, final Object value) {
    if (!type.isInstance(value)) {
      throw new IllegalArgumentException("Expected " + type.getSimpleName() + ", got " + value);
    }
    return type.cast(value);
  }
}
