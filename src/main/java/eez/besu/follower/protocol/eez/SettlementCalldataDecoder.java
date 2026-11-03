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

import eez.besu.follower.protocol.eez.abi.AbiDecodingException;
import eez.besu.follower.protocol.eez.abi.AbiFunction;

import org.hyperledger.besu.datatypes.Address;

import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;

/**
 * Extracts the EEZ batch from a top-level settlement transaction (10.2 step 2).
 *
 * <p>Two shapes are recognised: a call to the settlement wrapper whose only argument is the
 * unchanged batch (D.2.1 step 8 forwards it to EEZ), and a direct {@code EEZ.postAndVerifyBatch}.
 * The spec does not fix the wrapper's function name, so it is configurable. A settlement made
 * through an intermediate contract does not expose the batch in transaction calldata and is not
 * decodable here.
 */
public final class SettlementCalldataDecoder {

  /** {@code EEZ.postAndVerifyBatch(ProofSystemBatchPerVerificationEntries)}. */
  public static final AbiFunction POST_AND_VERIFY_BATCH = batchFunction("postAndVerifyBatch");

  /** Assumed wrapper entry point name, after the {@code submitCandidate} transaction of ch. 7. */
  public static final String DEFAULT_WRAPPER_FUNCTION_NAME = "submitCandidate";

  private final Address eez;
  private final Address wrapper;
  private final AbiFunction wrapperFunction;

  /**
   * The decoded batch and the {@code msg.sender} EEZ saw, which {@code bindMsgSenderInPublicInput}
   * folds into the public input.
   *
   * @param batch the decoded batch
   * @param eezCaller the wrapper, or the transaction sender for a direct EEZ call
   */
  public record SettlementCall(EezBatch batch, Address eezCaller) {}

  /**
   * Creates a decoder.
   *
   * @param eez the selected L1 EEZ contract
   * @param wrapper the active Rollup0 settlement wrapper
   * @param wrapperFunctionName the wrapper function taking the batch as its only argument
   */
  public SettlementCalldataDecoder(
      final Address eez, final Address wrapper, final String wrapperFunctionName) {
    this.eez = eez;
    this.wrapper = wrapper;
    this.wrapperFunction = batchFunction(wrapperFunctionName);
  }

  /**
   * Returns {@code name(ProofSystemBatchPerVerificationEntries)}.
   *
   * @param name the function name
   * @return the function
   */
  public static AbiFunction batchFunction(final String name) {
    return AbiFunction.parse(name + "(" + EezBatch.TYPE.canonical() + ")");
  }

  /**
   * Decodes a settlement transaction.
   *
   * @param from the transaction sender
   * @param to the transaction recipient
   * @param input the transaction calldata
   * @return the batch, or empty if the transaction is not a recognised settlement call
   * @throws AbiDecodingException if the selector matches but the batch is malformed
   */
  public Optional<SettlementCall> decode(final Address from, final Address to, final Bytes input) {
    if (wrapper.equals(to) && wrapperFunction.matches(input)) {
      return Optional.of(new SettlementCall(decodeBatch(wrapperFunction, input), wrapper));
    }
    if (eez.equals(to) && POST_AND_VERIFY_BATCH.matches(input)) {
      return Optional.of(new SettlementCall(decodeBatch(POST_AND_VERIFY_BATCH, input), from));
    }
    return Optional.empty();
  }

  private static EezBatch decodeBatch(final AbiFunction function, final Bytes input) {
    return EezBatch.fromAbi(AbiValues.list(function.decodeCall(input).getFirst()));
  }
}
