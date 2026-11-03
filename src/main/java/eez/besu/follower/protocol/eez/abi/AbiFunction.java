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

import java.util.List;

import org.apache.tuweni.bytes.Bytes;

/**
 * A Solidity function: name plus parameter types, giving its selector and call encoding.
 *
 * @param name the function name
 * @param parameters the parameter types
 */
public record AbiFunction(String name, AbiType.Tuple parameters) {

  /**
   * Creates a function from a canonical signature such as {@code transfer(address,uint256)}.
   *
   * @param signature the canonical signature
   * @return the function
   */
  public static AbiFunction parse(final String signature) {
    final int open = signature.indexOf('(');
    if (open <= 0) {
      throw new IllegalArgumentException("Not a function signature: " + signature);
    }
    return new AbiFunction(
        signature.substring(0, open), AbiType.parseTuple(signature.substring(open)));
  }

  /** Returns the canonical signature, e.g. {@code transfer(address,uint256)}. */
  public String signature() {
    return name + parameters.canonical();
  }

  /** Returns the 4-byte selector. */
  public Bytes selector() {
    return Abi.selector(signature());
  }

  /**
   * Returns {@code abi.encodeWithSelector(selector, arguments...)}.
   *
   * @param arguments one value per parameter
   * @return the calldata
   */
  public Bytes encodeCall(final List<?> arguments) {
    return Bytes.concatenate(selector(), Abi.encode(parameters, arguments));
  }

  /**
   * Decodes calldata for this function.
   *
   * @param calldata the calldata, starting with the selector
   * @return one decoded value per parameter
   * @throws AbiDecodingException if the selector differs or the arguments are malformed
   */
  public List<Object> decodeCall(final Bytes calldata) {
    if (!matches(calldata)) {
      throw new AbiDecodingException("Calldata does not start with the selector of " + signature());
    }
    return Abi.decode(parameters, calldata.slice(4));
  }

  /**
   * Returns whether calldata starts with this function's selector.
   *
   * @param calldata the calldata
   * @return whether the selector matches
   */
  public boolean matches(final Bytes calldata) {
    return calldata.size() >= 4 && calldata.slice(0, 4).equals(selector());
  }
}
