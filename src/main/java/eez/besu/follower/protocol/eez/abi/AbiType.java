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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A Solidity ABI type, restricted to the subset EEZ uses: {@code uint<M>}, {@code int<M>}, {@code
 * address}, {@code bool}, {@code bytes<M>}, {@code bytes}, dynamic arrays {@code T[]} and tuples.
 */
public sealed interface AbiType {

  /**
   * The canonical type string used in selectors and event signatures, e.g. {@code
   * (uint64,bytes)[]}.
   */
  String canonical();

  /** Whether the type is dynamic in the ABI sense (encoded out of line behind an offset). */
  boolean isDynamic();

  /**
   * Bytes the type occupies in its enclosing head: 32 for dynamic types and words, more for tuples.
   */
  default int headSize() {
    return 32;
  }

  /** {@code uint<bits>}; values are non-negative {@link java.math.BigInteger}s. */
  record Uint(int bits) implements AbiType {
    public Uint {
      checkBits(bits);
    }

    @Override
    public String canonical() {
      return "uint" + bits;
    }

    @Override
    public boolean isDynamic() {
      return false;
    }
  }

  /** {@code int<bits>}; values are two's-complement {@link java.math.BigInteger}s. */
  record Int(int bits) implements AbiType {
    public Int {
      checkBits(bits);
    }

    @Override
    public String canonical() {
      return "int" + bits;
    }

    @Override
    public boolean isDynamic() {
      return false;
    }
  }

  /** {@code address}; values are Besu {@link org.hyperledger.besu.datatypes.Address}es. */
  record AddressType() implements AbiType {
    @Override
    public String canonical() {
      return "address";
    }

    @Override
    public boolean isDynamic() {
      return false;
    }
  }

  /** {@code bool}; values are {@link Boolean}s. */
  record Bool() implements AbiType {
    @Override
    public String canonical() {
      return "bool";
    }

    @Override
    public boolean isDynamic() {
      return false;
    }
  }

  /** {@code bytes<size>}; values are tuweni {@code Bytes} of exactly {@code size} bytes. */
  record FixedBytes(int size) implements AbiType {
    public FixedBytes {
      if (size < 1 || size > 32) {
        throw new IllegalArgumentException("bytes" + size + " is not an ABI type");
      }
    }

    @Override
    public String canonical() {
      return "bytes" + size;
    }

    @Override
    public boolean isDynamic() {
      return false;
    }
  }

  /** {@code bytes}; values are tuweni {@code Bytes}. */
  record DynamicBytes() implements AbiType {
    @Override
    public String canonical() {
      return "bytes";
    }

    @Override
    public boolean isDynamic() {
      return true;
    }
  }

  /** {@code T[]}; values are {@link List}s. */
  record Array(AbiType element) implements AbiType {
    @Override
    public String canonical() {
      return element.canonical() + "[]";
    }

    @Override
    public boolean isDynamic() {
      return true;
    }
  }

  /** {@code (T1,...,Tn)}; values are {@link List}s with one element per component. */
  record Tuple(List<AbiType> components) implements AbiType {
    public Tuple {
      components = List.copyOf(components);
    }

    @Override
    public String canonical() {
      return components.stream().map(AbiType::canonical).collect(Collectors.joining(",", "(", ")"));
    }

    @Override
    public boolean isDynamic() {
      return components.stream().anyMatch(AbiType::isDynamic);
    }

    @Override
    public int headSize() {
      return isDynamic() ? 32 : components.stream().mapToInt(AbiType::headSize).sum();
    }
  }

  /**
   * Parses a canonical type string such as {@code (uint64,(address,bytes)[])[]}.
   *
   * @param type the type string, without spaces or parameter names
   * @return the parsed type
   */
  static AbiType parse(final String type) {
    final Parser parser = new Parser(type);
    final AbiType parsed = parser.type();
    if (parser.pos != type.length()) {
      throw new IllegalArgumentException("Trailing characters in ABI type: " + type);
    }
    return parsed;
  }

  /**
   * Parses a parenthesised, comma-separated parameter list such as {@code (address,bytes)} into a
   * tuple.
   *
   * @param parameters the parameter list
   * @return the tuple of parameter types
   */
  static Tuple parseTuple(final String parameters) {
    if (!(parse(parameters) instanceof Tuple tuple)) {
      throw new IllegalArgumentException("Not a tuple: " + parameters);
    }
    return tuple;
  }

  private static void checkBits(final int bits) {
    if (bits < 8 || bits > 256 || bits % 8 != 0) {
      throw new IllegalArgumentException("Invalid integer width " + bits);
    }
  }

  /** Recursive-descent parser over canonical type strings. */
  final class Parser {
    private final String text;
    private int pos;

    private Parser(final String text) {
      this.text = text;
    }

    private AbiType type() {
      AbiType base = text.startsWith("(", pos) ? tuple() : elementary();
      while (text.startsWith("[]", pos)) {
        pos += 2;
        base = new Array(base);
      }
      return base;
    }

    private Tuple tuple() {
      pos++;
      final List<AbiType> components = new ArrayList<>();
      if (text.startsWith(")", pos)) {
        pos++;
        return new Tuple(components);
      }
      while (true) {
        components.add(type());
        if (pos >= text.length()) {
          throw new IllegalArgumentException("Unterminated tuple in ABI type: " + text);
        }
        final char next = text.charAt(pos++);
        if (next == ')') {
          return new Tuple(components);
        }
        if (next != ',') {
          throw new IllegalArgumentException("Unexpected '" + next + "' in ABI type: " + text);
        }
      }
    }

    private AbiType elementary() {
      final int start = pos;
      while (pos < text.length() && Character.isLetterOrDigit(text.charAt(pos))) {
        pos++;
      }
      final String name = text.substring(start, pos);
      return switch (name) {
        case "address" -> new AddressType();
        case "bool" -> new Bool();
        case "bytes" -> new DynamicBytes();
        case "uint" -> new Uint(256);
        case "int" -> new Int(256);
        default -> sized(name);
      };
    }

    private AbiType sized(final String name) {
      try {
        if (name.startsWith("uint")) {
          return new Uint(Integer.parseInt(name.substring(4)));
        }
        if (name.startsWith("int")) {
          return new Int(Integer.parseInt(name.substring(3)));
        }
        if (name.startsWith("bytes")) {
          return new FixedBytes(Integer.parseInt(name.substring(5)));
        }
      } catch (final NumberFormatException e) {
        // fall through to the error below
      }
      throw new IllegalArgumentException("Unsupported ABI type '" + name + "' in " + text);
    }
  }
}
