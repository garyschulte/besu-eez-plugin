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

import eez.besu.follower.protocol.codec.EezMessage.Call;
import eez.besu.follower.protocol.codec.EezMessage.ChainOperation;
import eez.besu.follower.protocol.codec.EezMessage.CloseBlobStream;
import eez.besu.follower.protocol.codec.EezMessage.FinishCrossChainTransaction;
import eez.besu.follower.protocol.codec.EezMessage.InitiateCrossChainTransaction;
import eez.besu.follower.protocol.codec.EezMessage.ReturnFail;
import eez.besu.follower.protocol.codec.EezMessage.ReturnSuccess;

import java.util.ArrayList;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;

/**
 * The Rollup0 candidate protocol V1 profile over a decoded EEZ message stream (Rollup0 D.4.1, G.6):
 *
 * <pre>
 * ChainOperation(chain_id = rollup0EezRollupId, operations)
 * bracket[0] ... bracket[n - 1]
 * CloseBlobStream
 * </pre>
 *
 * where each bracket is exactly {@code InitiateCrossChainTransaction(0, 0x) / Call(to_chain =
 * rollup0EezRollupId, ...) / ReturnSuccess|ReturnFail / FinishCrossChainTransaction} and no bracket
 * follows a {@code ReturnFail}.
 */
public final class Rollup0Profile {

  /** The settlement L1's EEZ network id. */
  public static final long L1_CHAIN_ID = 0L;

  private static final int BRACKET_SIZE = 4;

  private Rollup0Profile() {}

  /**
   * Decodes a batch's EEZ stream and applies the Rollup0 V1 profile.
   *
   * @param blobs the batch's selected blobs in order
   * @param callData the batch's {@code callData} (empty under V1)
   * @param rollup0EezRollupId the EEZ rollup id assigned to the Rollup0 manager
   * @return the candidate's operations and action manifest
   * @throws PayloadDecodingException if either the EEZ format or the Rollup0 profile is violated
   */
  public static Rollup0Candidate decode(
      final List<Bytes> blobs, final Bytes callData, final long rollup0EezRollupId) {
    return validate(EezStreamDecoder.decode(blobs, callData), rollup0EezRollupId);
  }

  /**
   * Applies the Rollup0 V1 profile to messages that already passed EEZ stream validation.
   *
   * @param messages the decoded EEZ messages in stream order
   * @param rollup0EezRollupId the EEZ rollup id assigned to the Rollup0 manager
   * @return the candidate's operations and action manifest
   * @throws PayloadDecodingException if the messages do not match the profile
   */
  public static Rollup0Candidate validate(
      final List<EezMessage> messages, final long rollup0EezRollupId) {
    if (messages.isEmpty() || !(messages.getFirst() instanceof ChainOperation chainOperation)) {
      throw new PayloadDecodingException(
          "Rollup0 profile: first message must be ChainOperation, found "
              + (messages.isEmpty() ? "nothing" : messages.getFirst().type()));
    }
    if (chainOperation.chainId() != rollup0EezRollupId) {
      throw new PayloadDecodingException(
          String.format(
              "Rollup0 profile: ChainOperation chain_id %s is not the Rollup0 EEZ rollup id %s",
              Long.toUnsignedString(chainOperation.chainId()),
              Long.toUnsignedString(rollup0EezRollupId)));
    }
    final int last = messages.size() - 1;
    if (last == 0 || !(messages.get(last) instanceof CloseBlobStream)) {
      throw new PayloadDecodingException(
          "Rollup0 profile: stream must end with CloseBlobStream and carry no callData messages");
    }

    final List<ActionBracket> manifest = new ArrayList<>();
    int i = 1;
    while (i < last) {
      if (!manifest.isEmpty() && !manifest.getLast().success()) {
        throw new PayloadDecodingException(
            String.format(
                "Rollup0 profile: message %d (%s) follows a ReturnFail bracket",
                i, messages.get(i).type()));
      }
      if (i + BRACKET_SIZE > last) {
        throw new PayloadDecodingException(
            String.format(
                "Rollup0 profile: message %d (%s) does not start a complete action bracket",
                i, messages.get(i).type()));
      }
      manifest.add(
          readBracket(
              messages.subList(i, i + BRACKET_SIZE), manifest.size(), i, rollup0EezRollupId));
      i += BRACKET_SIZE;
    }
    return new Rollup0Candidate(chainOperation.operations(), manifest);
  }

  private static ActionBracket readBracket(
      final List<EezMessage> bracket,
      final int manifestIndex,
      final int firstMessage,
      final long rollup0EezRollupId) {
    if (!(bracket.get(0) instanceof InitiateCrossChainTransaction initiate)) {
      throw shapeViolation(
          manifestIndex, firstMessage, bracket.get(0), "InitiateCrossChainTransaction");
    }
    if (initiate.chainId() != L1_CHAIN_ID) {
      throw new PayloadDecodingException(
          String.format(
              "Rollup0 profile: bracket %d InitiateCrossChainTransaction chain_id %s is not 0",
              manifestIndex, Long.toUnsignedString(initiate.chainId())));
    }
    if (!initiate.txData().isEmpty()) {
      throw new PayloadDecodingException(
          String.format(
              "Rollup0 profile: bracket %d InitiateCrossChainTransaction tx_data is not empty (%d"
                  + " bytes)",
              manifestIndex, initiate.txData().size()));
    }
    if (!(bracket.get(1) instanceof Call call)) {
      throw shapeViolation(manifestIndex, firstMessage + 1, bracket.get(1), "Call");
    }
    if (call.toChain() != rollup0EezRollupId) {
      throw new PayloadDecodingException(
          String.format(
              "Rollup0 profile: bracket %d Call to_chain %s is not the Rollup0 EEZ rollup id %s",
              manifestIndex,
              Long.toUnsignedString(call.toChain()),
              Long.toUnsignedString(rollup0EezRollupId)));
    }
    final boolean success;
    final Bytes returnData;
    switch (bracket.get(2)) {
      case ReturnSuccess r -> {
        success = true;
        returnData = r.returnData();
      }
      case ReturnFail r -> {
        success = false;
        returnData = r.returnData();
      }
      default ->
          throw shapeViolation(
              manifestIndex, firstMessage + 2, bracket.get(2), "ReturnSuccess or ReturnFail");
    }
    if (!(bracket.get(3) instanceof FinishCrossChainTransaction)) {
      throw shapeViolation(
          manifestIndex, firstMessage + 3, bracket.get(3), "FinishCrossChainTransaction");
    }
    return new ActionBracket(
        manifestIndex,
        call.fromAddress(),
        call.toAddress(),
        call.value(),
        call.gas(),
        call.data(),
        success,
        returnData);
  }

  private static PayloadDecodingException shapeViolation(
      final int manifestIndex,
      final int messageIndex,
      final EezMessage found,
      final String expected) {
    return new PayloadDecodingException(
        String.format(
            "Rollup0 profile: bracket %d message %d is %s, expected %s",
            manifestIndex, messageIndex, found.type(), expected));
  }
}
