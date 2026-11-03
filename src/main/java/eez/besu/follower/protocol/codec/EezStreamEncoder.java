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

import java.io.ByteArrayOutputStream;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;

/**
 * Encodes EEZ messages into a version {@code 0x00} logical stream, always using shortest-form
 * varints. It does not check structural validity; decode the result for that.
 */
public final class EezStreamEncoder {

  /**
   * The two physical segments of an encoded stream.
   *
   * @param blobPortion the version byte and every message through {@code CloseBlobStream}, unpadded
   * @param callData the messages after {@code CloseBlobStream}
   */
  public record EncodedStream(Bytes blobPortion, Bytes callData) {

    /** Packs the blob portion into full-size blobs. */
    public List<Bytes> blobs() {
      return BlobPacking.pack(blobPortion);
    }
  }

  private EezStreamEncoder() {}

  /**
   * Encodes a blob-carrying stream: messages up to and including the first {@code CloseBlobStream}
   * go to the blob portion (after the version byte), the rest to {@code callData}.
   */
  public static EncodedStream encode(final List<EezMessage> messages) {
    final ByteArrayOutputStream blobPortion = new ByteArrayOutputStream();
    final ByteArrayOutputStream callData = new ByteArrayOutputStream();
    blobPortion.write(EezStreamDecoder.STREAM_VERSION);
    ByteArrayOutputStream out = blobPortion;
    for (final EezMessage message : messages) {
      writeMessage(message, out);
      if (message instanceof CloseBlobStream) {
        out = callData;
      }
    }
    return new EncodedStream(
        Bytes.wrap(blobPortion.toByteArray()), Bytes.wrap(callData.toByteArray()));
  }

  /**
   * Encodes a zero-blob stream: the version byte followed by every message, as {@code callData}.
   */
  public static Bytes encodeCallDataOnly(final List<EezMessage> messages) {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(EezStreamDecoder.STREAM_VERSION);
    messages.forEach(message -> writeMessage(message, out));
    return Bytes.wrap(out.toByteArray());
  }

  /** Encodes one message: type byte, then fields in wire order. */
  public static Bytes encodeMessage(final EezMessage message) {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    writeMessage(message, out);
    return Bytes.wrap(out.toByteArray());
  }

  private static void writeMessage(final EezMessage message, final ByteArrayOutputStream out) {
    out.write(message.type().code());
    switch (message) {
      case ChainOperation m -> {
        writeU64(m.chainId(), out);
        writeBytesField(m.operations(), out);
      }
      case InitiateCrossChainTransaction m -> {
        writeU64(m.chainId(), out);
        writeBytesField(m.txData(), out);
      }
      case Call m -> {
        writeU64(m.toChain(), out);
        out.writeBytes(m.fromAddress().getBytes().toArrayUnsafe());
        out.writeBytes(m.toAddress().getBytes().toArrayUnsafe());
        out.writeBytes(m.value().toBytes().reverse().toArrayUnsafe());
        writeU64(m.gas(), out);
        writeBytesField(m.data(), out);
      }
      case StaticCall m -> {
        writeU64(m.toChain(), out);
        out.writeBytes(m.fromAddress().getBytes().toArrayUnsafe());
        out.writeBytes(m.toAddress().getBytes().toArrayUnsafe());
        writeU64(m.gas(), out);
        writeBytesField(m.data(), out);
      }
      case ReturnSuccess m -> writeBytesField(m.returnData(), out);
      case ReturnFail m -> writeBytesField(m.returnData(), out);
      case CloseBlobStream m -> {}
      case Snapshot m -> {}
      case Revert m -> {}
      case FinishCrossChainTransaction m -> {}
    }
  }

  private static void writeU64(final long value, final ByteArrayOutputStream out) {
    for (int i = 0; i < 8; i++) {
      out.write((int) (value >>> (8 * i)) & 0xff);
    }
  }

  private static void writeBytesField(final Bytes value, final ByteArrayOutputStream out) {
    Uvarint32.write(value.size(), out);
    out.writeBytes(value.toArrayUnsafe());
  }
}
