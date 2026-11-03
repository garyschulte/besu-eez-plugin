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

import static java.util.Objects.requireNonNull;

import org.hyperledger.besu.datatypes.Address;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * One decoded EEZ logical-stream message (BLOB_FORMAT_SPEC §2). Chain ids, gas, and other {@code
 * u64} fields are carried in a {@code long} with unsigned semantics; compare them with {@link
 * Long#compareUnsigned} and print them with {@link Long#toUnsignedString}.
 */
public sealed interface EezMessage {

  /** The message's wire type. */
  EezMessageType type();

  /** Ends the blob portion of the stream; everything after it in the last blob is zero padding. */
  record CloseBlobStream() implements EezMessage {
    @Override
    public EezMessageType type() {
      return EezMessageType.CLOSE_BLOB_STREAM;
    }
  }

  /** Opaque operations for one chain (the EEZ spec's {@code RollupOperation}). */
  record ChainOperation(long chainId, Bytes operations) implements EezMessage {
    public ChainOperation {
      requireNonNull(operations);
    }

    @Override
    public EezMessageType type() {
      return EezMessageType.CHAIN_OPERATION;
    }
  }

  /** Opens a cross-chain transaction originating on {@code chainId}. */
  record InitiateCrossChainTransaction(long chainId, Bytes txData) implements EezMessage {
    public InitiateCrossChainTransaction {
      requireNonNull(txData);
    }

    @Override
    public EezMessageType type() {
      return EezMessageType.INITIATE_CROSS_CHAIN_TRANSACTION;
    }
  }

  /** A value-bearing cross-chain call; its source chain is the top of the context stack. */
  record Call(
      long toChain, Address fromAddress, Address toAddress, UInt256 value, long gas, Bytes data)
      implements EezMessage {
    public Call {
      requireNonNull(fromAddress);
      requireNonNull(toAddress);
      requireNonNull(value);
      requireNonNull(data);
    }

    @Override
    public EezMessageType type() {
      return EezMessageType.CALL;
    }
  }

  /** A read-only cross-chain call; carries no value. */
  record StaticCall(long toChain, Address fromAddress, Address toAddress, long gas, Bytes data)
      implements EezMessage {
    public StaticCall {
      requireNonNull(fromAddress);
      requireNonNull(toAddress);
      requireNonNull(data);
    }

    @Override
    public EezMessageType type() {
      return EezMessageType.STATIC_CALL;
    }
  }

  /** Successful result of the innermost outstanding call. */
  record ReturnSuccess(Bytes returnData) implements EezMessage {
    public ReturnSuccess {
      requireNonNull(returnData);
    }

    @Override
    public EezMessageType type() {
      return EezMessageType.RETURN_SUCCESS;
    }
  }

  /** Reverted result of the innermost outstanding call. */
  record ReturnFail(Bytes returnData) implements EezMessage {
    public ReturnFail {
      requireNonNull(returnData);
    }

    @Override
    public EezMessageType type() {
      return EezMessageType.RETURN_FAIL;
    }
  }

  /** Opens a forced-revert region. */
  record Snapshot() implements EezMessage {
    @Override
    public EezMessageType type() {
      return EezMessageType.SNAPSHOT;
    }
  }

  /** Closes the innermost forced-revert region, rolling it back. */
  record Revert() implements EezMessage {
    @Override
    public EezMessageType type() {
      return EezMessageType.REVERT;
    }
  }

  /** Closes the open cross-chain transaction. */
  record FinishCrossChainTransaction() implements EezMessage {
    @Override
    public EezMessageType type() {
      return EezMessageType.FINISH_CROSS_CHAIN_TRANSACTION;
    }
  }
}
