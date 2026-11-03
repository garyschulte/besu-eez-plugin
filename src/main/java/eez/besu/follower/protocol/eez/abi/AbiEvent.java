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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * A non-anonymous Solidity event whose indexed parameters are all static (true of every EEZ event).
 *
 * @param name the event name
 * @param parameters the parameters in declaration order
 */
public record AbiEvent(String name, List<Parameter> parameters) {

  /**
   * One event parameter.
   *
   * @param type the parameter type
   * @param indexed whether the parameter is carried in a topic
   */
  public record Parameter(AbiType type, boolean indexed) {}

  /**
   * Validates and copies the parameter list.
   *
   * @param name the event name
   * @param parameters the parameters in declaration order
   */
  public AbiEvent {
    parameters = List.copyOf(parameters);
    for (final Parameter parameter : parameters) {
      if (parameter.indexed() && parameter.type().isDynamic()) {
        throw new IllegalArgumentException("Indexed dynamic parameters are not supported");
      }
    }
    if (parameters.stream().filter(Parameter::indexed).count() > 3) {
      throw new IllegalArgumentException("At most three indexed parameters");
    }
  }

  /**
   * Parses a declaration such as {@code Foo(uint64 indexed,bytes32)}: canonical types, each
   * optionally followed by {@code " indexed"}.
   *
   * @param declaration the declaration
   * @return the event
   */
  public static AbiEvent parse(final String declaration) {
    final int open = declaration.indexOf('(');
    final String name = declaration.substring(0, open);
    final List<Parameter> parameters = new ArrayList<>();
    final String body = declaration.substring(open + 1, declaration.length() - 1);
    int depth = 0;
    int start = 0;
    for (int i = 0; i <= body.length(); i++) {
      final char c = i == body.length() ? ',' : body.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      } else if (c == ',' && depth == 0 && i > start) {
        final String item = body.substring(start, i).trim();
        final boolean indexed = item.endsWith(" indexed");
        final String type = indexed ? item.substring(0, item.length() - 8).trim() : item;
        parameters.add(new Parameter(AbiType.parse(type), indexed));
        start = i + 1;
      }
    }
    return new AbiEvent(name, parameters);
  }

  /** Returns the canonical signature, e.g. {@code Foo(uint64,bytes32)}. */
  public String signature() {
    return parameters.stream()
        .map(p -> p.type().canonical())
        .collect(Collectors.joining(",", name + "(", ")"));
  }

  /** Returns {@code topic0 = keccak256(signature)}. */
  public Bytes32 topic0() {
    return Abi.keccak256(Bytes.wrap(signature().getBytes(StandardCharsets.US_ASCII)));
  }

  /**
   * Decodes a log of this event into its parameters in declaration order.
   *
   * @param topics the log topics, including topic0
   * @param data the log data
   * @return one value per parameter
   * @throws AbiDecodingException if topic0, the topic count, or the data do not match
   */
  public List<Object> decode(final List<Bytes32> topics, final Bytes data) {
    final List<AbiType> dataTypes = new ArrayList<>();
    int indexedCount = 0;
    for (final Parameter parameter : parameters) {
      if (parameter.indexed()) {
        indexedCount++;
      } else {
        dataTypes.add(parameter.type());
      }
    }
    if (topics.size() != indexedCount + 1 || !topics.getFirst().equals(topic0())) {
      throw new AbiDecodingException("Log is not a " + signature() + " event");
    }
    final List<Object> dataValues = Abi.decode(new AbiType.Tuple(dataTypes), data);
    final List<Object> values = new ArrayList<>(parameters.size());
    int topic = 1;
    int dataIndex = 0;
    for (final Parameter parameter : parameters) {
      values.add(
          parameter.indexed()
              ? Abi.decodeWord(parameter.type(), topics.get(topic++))
              : dataValues.get(dataIndex++));
    }
    return values;
  }
}
