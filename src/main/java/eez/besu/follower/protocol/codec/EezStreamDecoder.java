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
import eez.besu.follower.protocol.codec.EezMessage.Revert;
import eez.besu.follower.protocol.codec.EezMessage.Snapshot;
import eez.besu.follower.protocol.codec.EezMessage.StaticCall;

import org.hyperledger.besu.datatypes.Address;

import java.util.ArrayList;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * Decodes and validates an EEZ logical message stream, version {@code 0x00} (BLOB_FORMAT_SPEC). The
 * stream is the batch's blobs in order followed by its {@code callData}; any violation of §5.1
 * rejects the whole stream.
 *
 * <p>This layer does not check which rollups approved the stream (§5.2) or what took effect (§5.3);
 * those need L1 context the caller owns.
 */
public final class EezStreamDecoder {

  /** The only stream version this decoder accepts. */
  public static final int STREAM_VERSION = 0x00;

  private EezStreamDecoder() {}

  /**
   * Decodes the logical stream of one batch.
   *
   * @param blobs the batch's selected blobs in order, each exactly {@value BlobPacking#BLOB_SIZE}
   *     bytes; may be empty for a calldata-only batch
   * @param callData the batch's {@code callData}: the stream tail, or the whole stream when there
   *     are no blobs
   * @return every message in stream order, including {@code CloseBlobStream} when blobs exist
   * @throws PayloadDecodingException if the stream violates any format rule
   */
  public static List<EezMessage> decode(final List<Bytes> blobs, final Bytes callData) {
    if (blobs.isEmpty()) {
      return decodeCallDataOnly(callData);
    }
    return decodeLogicalStream(BlobPacking.unpack(blobs), callData);
  }

  /**
   * Decodes an already-unpacked stream whose blob portion is non-empty.
   *
   * @param blobPortion the recovered blob bytes (version byte, messages, close marker, padding)
   * @param callDataTail the batch {@code callData}, continuing the stream after the close marker
   * @return every message in stream order
   */
  public static List<EezMessage> decodeLogicalStream(
      final Bytes blobPortion, final Bytes callDataTail) {
    final Decoder decoder = new Decoder();
    final ByteReader blobReader = new ByteReader(blobPortion, "blob portion");
    decoder.readVersion(blobReader);
    decoder.decodeSegment(blobReader, true);
    if (!decoder.closeSeen) {
      throw new PayloadDecodingException("blob portion has no CloseBlobStream");
    }
    decoder.decodeSegment(new ByteReader(callDataTail, "callData"), false);
    decoder.requireClosedBrackets();
    return decoder.messages;
  }

  /**
   * Decodes a zero-blob batch: the stream starts in {@code callData} with the version byte and ends
   * with it, and {@code CloseBlobStream} must not appear.
   *
   * @param callData the whole stream
   * @return every message in stream order
   */
  public static List<EezMessage> decodeCallDataOnly(final Bytes callData) {
    final Decoder decoder = new Decoder();
    final ByteReader reader = new ByteReader(callData, "callData");
    decoder.readVersion(reader);
    decoder.decodeSegment(reader, false);
    decoder.requireClosedBrackets();
    return decoder.messages;
  }

  /** Single-pass decoder state: the context stack of §1.2 and the open snapshots' depths. */
  private static final class Decoder {
    private final List<EezMessage> messages = new ArrayList<>();
    // only the depth of the context stack matters for validity; its contents are the chain ids
    private final List<Long> contextStack = new ArrayList<>();
    private final List<Integer> snapshotDepths = new ArrayList<>();
    private boolean closeSeen;

    void readVersion(final ByteReader reader) {
      if (!reader.hasRemaining()) {
        throw new PayloadDecodingException("stream is empty: missing version byte");
      }
      final int version = reader.readUnsignedByte();
      if (version != STREAM_VERSION) {
        throw new PayloadDecodingException(String.format("unknown stream version 0x%02x", version));
      }
    }

    void decodeSegment(final ByteReader reader, final boolean blobPortion) {
      while (reader.hasRemaining()) {
        final int offset = reader.position();
        final int code = reader.readUnsignedByte();
        final EezMessageType type =
            EezMessageType.fromCode(code)
                .orElseThrow(
                    () ->
                        new PayloadDecodingException(
                            String.format(
                                "invalid message type %d at %s offset %d",
                                code, segmentName(blobPortion), offset)));
        if (type == EezMessageType.CLOSE_BLOB_STREAM) {
          if (!blobPortion) {
            throw new PayloadDecodingException(
                String.format("CloseBlobStream in callData at offset %d", offset));
          }
          closeSeen = true;
          messages.add(new CloseBlobStream());
          requireZeroPadding(reader);
          return;
        }
        final EezMessage message = readFields(type, reader);
        validatePlacement(message, segmentName(blobPortion), offset);
        messages.add(message);
      }
    }

    private static String segmentName(final boolean blobPortion) {
      return blobPortion ? "blob portion" : "callData";
    }

    private static void requireZeroPadding(final ByteReader reader) {
      while (reader.hasRemaining()) {
        final int offset = reader.position();
        final int b = reader.readUnsignedByte();
        if (b != 0) {
          throw new PayloadDecodingException(
              String.format(
                  "nonzero padding byte 0x%02x after CloseBlobStream at blob portion offset %d",
                  b, offset));
        }
      }
    }

    private static EezMessage readFields(final EezMessageType type, final ByteReader reader) {
      return switch (type) {
        case CHAIN_OPERATION ->
            new ChainOperation(
                reader.readU64LittleEndian("ChainOperation.chain_id"),
                readBytesField(reader, "ChainOperation.operations"));
        case INITIATE_CROSS_CHAIN_TRANSACTION ->
            new InitiateCrossChainTransaction(
                reader.readU64LittleEndian("InitiateCrossChainTransaction.chain_id"),
                readBytesField(reader, "InitiateCrossChainTransaction.tx_data"));
        case CALL ->
            new Call(
                reader.readU64LittleEndian("Call.to_chain"),
                Address.wrap(reader.readBytes(Address.SIZE, "Call.from_address")),
                Address.wrap(reader.readBytes(Address.SIZE, "Call.to_address")),
                UInt256.fromBytes(reader.readBytes(32, "Call.value").reverse()),
                reader.readU64LittleEndian("Call.gas"),
                readBytesField(reader, "Call.data"));
        case STATIC_CALL ->
            new StaticCall(
                reader.readU64LittleEndian("StaticCall.to_chain"),
                Address.wrap(reader.readBytes(Address.SIZE, "StaticCall.from_address")),
                Address.wrap(reader.readBytes(Address.SIZE, "StaticCall.to_address")),
                reader.readU64LittleEndian("StaticCall.gas"),
                readBytesField(reader, "StaticCall.data"));
        case RETURN_SUCCESS ->
            new ReturnSuccess(readBytesField(reader, "ReturnSuccess.return_data"));
        case RETURN_FAIL -> new ReturnFail(readBytesField(reader, "ReturnFail.return_data"));
        case SNAPSHOT -> new Snapshot();
        case REVERT -> new Revert();
        case FINISH_CROSS_CHAIN_TRANSACTION -> new FinishCrossChainTransaction();
        case CLOSE_BLOB_STREAM -> throw new IllegalStateException("handled by caller");
      };
    }

    private static Bytes readBytesField(final ByteReader reader, final String field) {
      final long length = Uvarint32.readLenient(reader, field + " length");
      return reader.readBytes(length, field);
    }

    /** Bracket discipline (§5.1 condition 6) and placement (condition 7) over the stack of §1.2. */
    private void validatePlacement(
        final EezMessage message, final String segment, final int offset) {
      final String where = String.format("%s at %s offset %d", message.type(), segment, offset);
      switch (message) {
        case ChainOperation ignored -> {
          if (!contextStack.isEmpty()) {
            throw new PayloadDecodingException(where + ": inside a cross-chain transaction");
          }
        }
        case InitiateCrossChainTransaction initiate -> {
          if (!contextStack.isEmpty()) {
            throw new PayloadDecodingException(where + ": nested cross-chain transaction");
          }
          contextStack.add(initiate.chainId());
        }
        case Call call -> pushCall(call.toChain(), where);
        case StaticCall call -> pushCall(call.toChain(), where);
        case ReturnSuccess ignored -> popReturn(where);
        case ReturnFail ignored -> popReturn(where);
        case Snapshot ignored -> {
          if (contextStack.isEmpty()) {
            throw new PayloadDecodingException(where + ": outside a cross-chain transaction");
          }
          snapshotDepths.add(contextStack.size());
        }
        case Revert ignored -> {
          if (snapshotDepths.isEmpty()) {
            throw new PayloadDecodingException(where + ": no open Snapshot");
          }
          if (contextStack.size() != snapshotDepths.getLast()) {
            throw new PayloadDecodingException(
                where + ": a call opened after its Snapshot has not returned");
          }
          snapshotDepths.removeLast();
        }
        case FinishCrossChainTransaction ignored -> {
          if (contextStack.isEmpty()) {
            throw new PayloadDecodingException(where + ": outside a cross-chain transaction");
          }
          if (contextStack.size() > 1) {
            throw new PayloadDecodingException(where + ": a call has not returned");
          }
          if (!snapshotDepths.isEmpty()) {
            throw new PayloadDecodingException(where + ": a Snapshot is still open");
          }
          contextStack.removeLast();
        }
        case CloseBlobStream ignored -> throw new IllegalStateException("handled by caller");
      }
    }

    private void pushCall(final long toChain, final String where) {
      if (contextStack.isEmpty()) {
        throw new PayloadDecodingException(where + ": outside a cross-chain transaction");
      }
      contextStack.add(toChain);
    }

    private void popReturn(final String where) {
      if (contextStack.size() < 2) {
        throw new PayloadDecodingException(where + ": no outstanding call");
      }
      if (!snapshotDepths.isEmpty() && contextStack.size() - 1 < snapshotDepths.getLast()) {
        throw new PayloadDecodingException(
            where + ": returns a call opened before the innermost open Snapshot");
      }
      contextStack.removeLast();
    }

    void requireClosedBrackets() {
      if (!contextStack.isEmpty()) {
        throw new PayloadDecodingException(
            "stream ends inside an open cross-chain transaction (context depth "
                + contextStack.size()
                + ")");
      }
      // snapshots only open inside a transaction and must close before it finishes
    }
  }
}
