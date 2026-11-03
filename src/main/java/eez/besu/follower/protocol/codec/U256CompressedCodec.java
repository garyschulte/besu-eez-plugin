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

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * The EEZ compressed {@code uint256} codec (U256_COMPRESSED_CODEC.md): one format byte, then 0–32
 * little-endian payload bytes. Codes {@code 0..32} are literals, {@code 33..252} encode {@code m *
 * base^exp} for base 10 then base 256, and {@code 253..255} are reserved.
 *
 * <p>Blob stream version {@code 0x00} does not use this codec ({@code Call.value} is a plain
 * 32-byte little-endian word there); it is listed in FUTURE_OPTIMIZATIONS.md for a later stream
 * version.
 */
public final class U256CompressedCodec {

  /**
   * One decoded value and the offset just past its encoding.
   *
   * @param value the decoded value
   * @param nextOffset the offset of the byte after the encoding
   */
  public record Decoded(UInt256 value, int nextOffset) {}

  private record Scientific(int base, int exp, int length) {}

  private static final int LITERAL_MAX = 32;
  private static final int[] BASES = {10, 256};
  private static final BigInteger TARGET = BigInteger.TEN.pow(28);
  private static final BigInteger MAX_U256 = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);

  private static final Scientific[] CODE_TO_SCIENTIFIC = new Scientific[256];
  private static final Map<Scientific, Integer> SCIENTIFIC_TO_CODE = new HashMap<>();
  private static final Map<Integer, Integer> MAX_EXP = new HashMap<>();

  static {
    int code = LITERAL_MAX + 1;
    for (final int base : BASES) {
      for (int length = 1; ; length++) {
        final int maxExp = maxExp(base, length);
        if (maxExp < 1) {
          break;
        }
        for (int exp = 1; exp <= maxExp; exp++) {
          final Scientific scientific = new Scientific(base, exp, length);
          CODE_TO_SCIENTIFIC[code] = scientific;
          SCIENTIFIC_TO_CODE.put(scientific, code);
          MAX_EXP.merge(base, exp, Math::max);
          code++;
        }
      }
    }
    if (code != 253) {
      throw new IllegalStateException("scientific codes must end at 252, ended at " + (code - 1));
    }
  }

  private U256CompressedCodec() {}

  /** Smallest {@code exp >= 1} with {@code (2^(8L) - 1) * base^exp >= 10^28}; 0 ends the ladder. */
  private static int maxExp(final int base, final int length) {
    final BigInteger mantissaMax = BigInteger.ONE.shiftLeft(8 * length).subtract(BigInteger.ONE);
    if (mantissaMax.compareTo(TARGET) >= 0) {
      return 0;
    }
    int exp = 1;
    while (mantissaMax.multiply(BigInteger.valueOf(base).pow(exp)).compareTo(TARGET) < 0) {
      exp++;
    }
    return exp;
  }

  /** Encodes {@code value} in its canonical form (§4). */
  public static Bytes encode(final UInt256 value) {
    final BigInteger v = value.toBigInteger();
    final int literalLength = byteLength(v);
    Bytes best = Bytes.concatenate(Bytes.of(literalLength), littleEndian(v, literalLength));
    // ordering key: (size, base rank with 256 first, exponent); the literal ranks last at equal
    // size
    int bestSize = 1 + literalLength;
    int bestRank = 2;
    int bestExp = 0;
    for (final int base : BASES) {
      final BigInteger bigBase = BigInteger.valueOf(base);
      for (int exp = 1; exp <= MAX_EXP.get(base); exp++) {
        final BigInteger[] quotient = v.divideAndRemainder(bigBase.pow(exp));
        if (quotient[1].signum() != 0 || quotient[0].signum() == 0) {
          continue;
        }
        final int length = byteLength(quotient[0]);
        final Integer code = SCIENTIFIC_TO_CODE.get(new Scientific(base, exp, length));
        if (code == null) {
          continue;
        }
        final int size = 1 + length;
        final int rank = base == 256 ? 0 : 1;
        if (size < bestSize
            || (size == bestSize && (rank < bestRank || (rank == bestRank && exp < bestExp)))) {
          best = Bytes.concatenate(Bytes.of(code), littleEndian(quotient[0], length));
          bestSize = size;
          bestRank = rank;
          bestExp = exp;
        }
      }
    }
    return best;
  }

  /**
   * Decodes one value at {@code offset}. Non-canonical encodings are accepted.
   *
   * @throws PayloadDecodingException for a reserved format byte, a truncated payload, or a value
   *     above {@code 2^256 - 1}
   */
  public static Decoded decode(final Bytes input, final int offset) {
    if (offset >= input.size()) {
      throw new PayloadDecodingException(
          "compressed u256: missing format byte at offset " + offset);
    }
    final int format = input.get(offset) & 0xff;
    final int payloadStart = offset + 1;
    if (format <= LITERAL_MAX) {
      final Bytes payload = payload(input, payloadStart, format, "literal payload");
      return new Decoded(UInt256.valueOf(fromLittleEndian(payload)), payloadStart + format);
    }
    final Scientific scientific = CODE_TO_SCIENTIFIC[format];
    if (scientific == null) {
      throw new PayloadDecodingException("compressed u256: reserved/invalid format byte " + format);
    }
    final Bytes mantissa = payload(input, payloadStart, scientific.length(), "mantissa");
    final BigInteger value =
        fromLittleEndian(mantissa)
            .multiply(BigInteger.valueOf(scientific.base()).pow(scientific.exp()));
    if (value.compareTo(MAX_U256) > 0) {
      throw new PayloadDecodingException("compressed u256: decoded value exceeds 2^256-1");
    }
    return new Decoded(UInt256.valueOf(value), payloadStart + scientific.length());
  }

  private static Bytes payload(
      final Bytes input, final int start, final int length, final String what) {
    if (length > input.size() - start) {
      throw new PayloadDecodingException(
          String.format(
              "compressed u256: truncated %s at offset %d (need %d bytes, %d remain)",
              what, start, length, input.size() - start));
    }
    return input.slice(start, length);
  }

  private static int byteLength(final BigInteger value) {
    return (value.bitLength() + 7) / 8;
  }

  private static Bytes littleEndian(final BigInteger value, final int length) {
    final byte[] out = new byte[length];
    for (int i = 0; i < length; i++) {
      out[i] = value.shiftRight(8 * i).byteValue();
    }
    return Bytes.wrap(out);
  }

  private static BigInteger fromLittleEndian(final Bytes payload) {
    return new BigInteger(1, payload.reverse().toArrayUnsafe());
  }
}
