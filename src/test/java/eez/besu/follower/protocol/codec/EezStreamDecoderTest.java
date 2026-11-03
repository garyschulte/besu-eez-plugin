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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import eez.besu.follower.protocol.codec.EezStreamEncoder.EncodedStream;

import org.hyperledger.besu.datatypes.Address;

import java.util.ArrayList;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * EEZ logical stream (BLOB_FORMAT_SPEC). There are no published byte-level stream vectors in
 * eez-core-protocol beyond the §4 packing example; the layout tests below are hand-computed from
 * §1.1/§2 and mirror test/blob/BlobCodec.t.sol, and the round trips use this package's encoder.
 */
class EezStreamDecoderTest {

  static final long L1 = 0;
  static final long L2A = 1;
  static final long L2B = 2;
  static final Address USER = Address.fromHexString("0x00000000000000000000000000000000000a11ce");
  static final Address TARGET_A =
      Address.fromHexString("0x000000000000000000000000000000000000aaaa");
  static final Address TARGET_B =
      Address.fromHexString("0x000000000000000000000000000000000000bbbb");

  static Bytes b(final String text) {
    return Bytes.wrap(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  static EezMessage initiate(final long chain, final String txData) {
    return new InitiateCrossChainTransaction(chain, b(txData));
  }

  static EezMessage call(final long toChain, final String data) {
    return new Call(toChain, USER, TARGET_A, UInt256.ZERO, 0, b(data));
  }

  static EezMessage staticCall(final long toChain, final String data) {
    return new StaticCall(toChain, TARGET_A, TARGET_B, 30_000, b(data));
  }

  static EezMessage ok(final String data) {
    return new ReturnSuccess(b(data));
  }

  static EezMessage fail(final String data) {
    return new ReturnFail(b(data));
  }

  static EezMessage chainOp(final long chain, final String ops) {
    return new ChainOperation(chain, b(ops));
  }

  static final EezMessage SNAPSHOT = new Snapshot();
  static final EezMessage REVERT = new Revert();
  static final EezMessage FINISH = new FinishCrossChainTransaction();
  static final EezMessage CLOSE = new CloseBlobStream();

  /** The reference flow of BlobCodec.t.sol: L1 to L2A, L2A to L2B, both return. */
  static List<EezMessage> exampleFlow() {
    return List.of(
        initiate(L1, "tx-data"),
        call(L2A, "call-a"),
        call(L2B, "call-b"),
        ok("ret-b"),
        ok("ret-a"),
        FINISH,
        CLOSE);
  }

  private static List<EezMessage> roundTripThroughBlobs(final List<EezMessage> messages) {
    final EncodedStream encoded = EezStreamEncoder.encode(messages);
    return EezStreamDecoder.decode(encoded.blobs(), encoded.callData());
  }

  private static void assertRejected(final List<EezMessage> messages, final String reason) {
    final EncodedStream encoded = EezStreamEncoder.encode(messages);
    assertRejected(encoded.blobPortion(), encoded.callData(), reason);
  }

  private static void assertRejected(
      final Bytes blobPortion, final Bytes callData, final String reason) {
    assertThatThrownBy(() -> EezStreamDecoder.decodeLogicalStream(blobPortion, callData))
        .isInstanceOf(PayloadDecodingException.class)
        .hasMessageContaining(reason);
  }

  @Nested
  class WireLayout {

    @Test
    void versionByteAndLittleEndianChainId() {
      final EncodedStream encoded =
          EezStreamEncoder.encode(
              List.of(new ChainOperation(0x0102030405060708L, Bytes.of(0xaa)), CLOSE));
      assertThat(encoded.blobPortion())
          .isEqualTo(Bytes.fromHexString("0x00" + "02" + "0807060504030201" + "01aa" + "01"));
      assertThat(encoded.callData()).isEqualTo(Bytes.EMPTY);
    }

    @Test
    void callLayout() {
      final Call call =
          new Call(
              0x05L,
              USER,
              TARGET_A,
              UInt256.valueOf(0x0102),
              0x030405L,
              Bytes.fromHexString("0xdead"));
      assertThat(EezStreamEncoder.encodeMessage(call))
          .isEqualTo(
              Bytes.fromHexString(
                  "0x04"
                      + "0500000000000000"
                      + "00000000000000000000000000000000000a11ce"
                      + "000000000000000000000000000000000000aaaa"
                      + "0201"
                      + "00".repeat(30)
                      + "0504030000000000"
                      + "02dead"));
    }

    @Test
    void staticCallAndReturnLayouts() {
      assertThat(
              EezStreamEncoder.encodeMessage(new StaticCall(0, TARGET_A, TARGET_B, 1, Bytes.EMPTY)))
          .isEqualTo(
              Bytes.fromHexString(
                  "0x05"
                      + "0000000000000000"
                      + "000000000000000000000000000000000000aaaa"
                      + "000000000000000000000000000000000000bbbb"
                      + "0100000000000000"
                      + "00"));
      assertThat(EezStreamEncoder.encodeMessage(ok("A")))
          .isEqualTo(Bytes.fromHexString("0x060141"));
      assertThat(EezStreamEncoder.encodeMessage(fail(""))).isEqualTo(Bytes.fromHexString("0x0700"));
      assertThat(EezStreamEncoder.encodeMessage(SNAPSHOT)).isEqualTo(Bytes.of(8));
      assertThat(EezStreamEncoder.encodeMessage(REVERT)).isEqualTo(Bytes.of(9));
      assertThat(EezStreamEncoder.encodeMessage(FINISH)).isEqualTo(Bytes.of(10));
      assertThat(EezStreamEncoder.encodeMessage(CLOSE)).isEqualTo(Bytes.of(1));
    }

    @Test
    void decodesHandWrittenCallWithLittleEndianValue() {
      final Bytes stream =
          Bytes.fromHexString(
              "0x00"
                  + "03"
                  + "0000000000000000"
                  + "00"
                  + "04"
                  + "0700000000000000"
                  + "00000000000000000000000000000000000a11ce"
                  + "000000000000000000000000000000000000aaaa"
                  + "00000000000000000000000000000000000000000000000000000000000000ff"
                  + "1027000000000000"
                  + "00"
                  + "06"
                  + "00"
                  + "0a"
                  + "01");
      final List<EezMessage> messages = EezStreamDecoder.decodeLogicalStream(stream, Bytes.EMPTY);
      assertThat(messages.get(1))
          .isEqualTo(
              new Call(
                  7,
                  USER,
                  TARGET_A,
                  UInt256.ONE.shiftLeft(248).multiply(UInt256.valueOf(0xff)),
                  10_000,
                  Bytes.EMPTY));
    }

    @Test
    void varint300IsTwoBytes() {
      final Bytes payload = Bytes.wrap(new byte[300]);
      assertThat(EezStreamEncoder.encodeMessage(new ReturnSuccess(payload)).slice(0, 3))
          .isEqualTo(Bytes.fromHexString("0x06ac02"));
    }

    @Test
    void acceptsNonMinimalVarint() {
      // §5.1 condition 8: varint(1) written as 0x81 0x00 still decodes
      final Bytes stream =
          Bytes.fromHexString("0x00" + "02" + "0700000000000000" + "8100" + "aa" + "01");
      assertThat(EezStreamDecoder.decodeLogicalStream(stream, Bytes.EMPTY))
          .containsExactly(new ChainOperation(7, Bytes.of(0xaa)), CLOSE);
    }

    @Test
    void acceptsFiveByteNonMinimalVarint() {
      final Bytes stream =
          Bytes.fromHexString("0x00" + "02" + "0700000000000000" + "8180808000" + "aa" + "01");
      assertThat(EezStreamDecoder.decodeLogicalStream(stream, Bytes.EMPTY))
          .containsExactly(new ChainOperation(7, Bytes.of(0xaa)), CLOSE);
    }

    @Test
    void decodedFieldsArePinnedCopies() {
      final List<EezMessage> messages =
          EezStreamDecoder.decodeLogicalStream(
              Bytes.fromHexString("0x00020100000000000000" + "02beef" + "01"), Bytes.EMPTY);
      assertThat(((ChainOperation) messages.getFirst()).operations())
          .isEqualTo(Bytes.fromHexString("0xbeef"));
    }
  }

  @Nested
  class RoundTrips {

    @Test
    void exampleFlowThroughBlobs() {
      assertThat(roundTripThroughBlobs(exampleFlow())).isEqualTo(exampleFlow());
    }

    @Test
    void callsWithValueAndGas() {
      final List<EezMessage> messages =
          List.of(
              initiate(L2A, "rlp-tx"),
              new Call(
                  L2B,
                  USER,
                  TARGET_B,
                  UInt256.valueOf(1_000_000_000_000_000_000L),
                  250_000,
                  b("bounded")),
              new StaticCall(L1, TARGET_B, TARGET_A, 30_000, b("bounded-read")),
              ok("read-result"),
              ok("call-result"),
              FINISH,
              CLOSE);
      assertThat(roundTripThroughBlobs(messages)).isEqualTo(messages);
    }

    @Test
    void allMessageTypesWithCallDataContinuation() {
      final List<EezMessage> messages =
          List.of(
              chainOp(L2A, "deadbeef"),
              chainOp(L2B, ""),
              initiate(L2A, "rlp-tx"),
              new Call(L1, USER, TARGET_A, UInt256.MAX_VALUE, -1L, b("with-value")),
              staticCall(L2B, "read"),
              ok("read-result"),
              fail("revert-payload"),
              SNAPSHOT,
              call(L2B, "reverted-later"),
              ok(""),
              REVERT,
              FINISH,
              CLOSE,
              chainOp(L2A, "in-calldata-tail"),
              initiate(L1, ""),
              call(L2A, "tail-call"),
              ok("tail-ret"),
              FINISH);
      final EncodedStream encoded = EezStreamEncoder.encode(messages);
      assertThat(encoded.callData().isEmpty()).isFalse();
      assertThat(EezStreamDecoder.decode(encoded.blobs(), encoded.callData())).isEqualTo(messages);
    }

    @Test
    void nestedSnapshots() {
      final List<EezMessage> messages =
          List.of(
              initiate(L2A, ""),
              SNAPSHOT,
              call(L2B, "1"),
              ok(""),
              SNAPSHOT,
              call(3, "2"),
              ok(""),
              REVERT,
              REVERT,
              FINISH,
              CLOSE);
      assertThat(roundTripThroughBlobs(messages)).isEqualTo(messages);
    }

    @Test
    void snapshotInsideCalleeContext() {
      final List<EezMessage> messages =
          List.of(
              initiate(L1, ""),
              call(L2A, ""),
              SNAPSHOT,
              call(L2B, ""),
              ok(""),
              REVERT,
              ok(""),
              FINISH,
              CLOSE);
      assertThat(roundTripThroughBlobs(messages)).isEqualTo(messages);
    }

    @Test
    void transactionMaySpanCloseBlobStream() {
      // CloseBlobStream has no effect on the context stack (§1.2); the tail continues the stream
      final List<EezMessage> messages =
          List.of(initiate(L1, ""), call(L2A, ""), CLOSE, ok(""), FINISH);
      assertThat(roundTripThroughBlobs(messages)).isEqualTo(messages);
    }

    @Test
    void messageSpansBlobBoundary() {
      final Bytes big = Bytes.wrap(new byte[BlobPacking.STREAM_BYTES_PER_BLOB + 100]);
      final List<EezMessage> messages = List.of(new ChainOperation(L2A, big), CLOSE);
      final EncodedStream encoded = EezStreamEncoder.encode(messages);
      assertThat(encoded.blobs()).hasSize(2);
      assertThat(roundTripThroughBlobs(messages)).isEqualTo(messages);
    }

    @Test
    void closeOnlyStream() {
      assertThat(roundTripThroughBlobs(List.of(CLOSE))).containsExactly(CLOSE);
    }

    @Test
    void callDataOnlyBatch() {
      final List<EezMessage> messages =
          List.of(chainOp(L2A, "ops"), initiate(L1, ""), call(L2A, ""), fail("x"), FINISH);
      final Bytes callData = EezStreamEncoder.encodeCallDataOnly(messages);
      assertThat(callData.get(0)).isEqualTo((byte) 0);
      assertThat(EezStreamDecoder.decode(List.of(), callData)).isEqualTo(messages);
    }

    @Test
    void callDataOnlyBatchWithVersionByteOnly() {
      assertThat(EezStreamDecoder.decode(List.of(), Bytes.of(0))).isEmpty();
    }
  }

  @Nested
  class FormatRejections {

    @Test
    void emptyBlobPortion() {
      assertRejected(Bytes.EMPTY, Bytes.EMPTY, "missing version byte");
    }

    @Test
    void emptyCallDataOnlyStream() {
      assertThatThrownBy(() -> EezStreamDecoder.decode(List.of(), Bytes.EMPTY))
          .isInstanceOf(PayloadDecodingException.class)
          .hasMessageContaining("missing version byte");
    }

    @Test
    void unknownVersion() {
      assertRejected(Bytes.fromHexString("0x0101"), Bytes.EMPTY, "unknown stream version 0x01");
    }

    @Test
    void unknownVersionInCallDataOnlyStream() {
      assertThatThrownBy(() -> EezStreamDecoder.decode(List.of(), Bytes.fromHexString("0x02")))
          .hasMessageContaining("unknown stream version 0x02");
    }

    @Test
    void missingCloseBlobStream() {
      final Bytes blobPortion = EezStreamEncoder.encode(List.of(chainOp(L2A, "ops"))).blobPortion();
      assertRejected(blobPortion, Bytes.EMPTY, "blob portion has no CloseBlobStream");
    }

    @Test
    void missingCloseBlobStreamInPaddedBlobHitsTypeZero() {
      final EncodedStream encoded = EezStreamEncoder.encode(List.of(chainOp(L2A, "ops")));
      assertThatThrownBy(() -> EezStreamDecoder.decode(encoded.blobs(), Bytes.EMPTY))
          .isInstanceOf(PayloadDecodingException.class)
          .hasMessageContaining("invalid message type 0 at blob portion offset 14");
    }

    @Test
    void typeZero() {
      assertRejected(Bytes.fromHexString("0x0000"), Bytes.EMPTY, "invalid message type 0");
    }

    @Test
    void unknownTypes() {
      assertRejected(Bytes.fromHexString("0x000b"), Bytes.EMPTY, "invalid message type 11");
      assertRejected(Bytes.fromHexString("0x00ff"), Bytes.EMPTY, "invalid message type 255");
      assertRejected(
          Bytes.fromHexString("0x0001"),
          Bytes.fromHexString("0x0b"),
          "invalid message type 11 at callData");
    }

    @Test
    void nonZeroPaddingAfterClose() {
      assertRejected(
          Bytes.fromHexString("0x000100ff"),
          Bytes.EMPTY,
          "nonzero padding byte 0xff after CloseBlobStream at blob portion offset 3");
    }

    @Test
    void nonZeroPaddingDeepInLastBlob() {
      final List<Bytes> blobs = EezStreamEncoder.encode(List.of(CLOSE)).blobs();
      final org.apache.tuweni.bytes.MutableBytes blob = blobs.getFirst().mutableCopy();
      blob.set(BlobPacking.BLOB_SIZE - 1, (byte) 1);
      assertThatThrownBy(() -> EezStreamDecoder.decode(List.of(blob), Bytes.EMPTY))
          .hasMessageContaining("nonzero padding");
    }

    @Test
    void secondCloseInBlobPortionIsNonZeroPadding() {
      assertRejected(Bytes.fromHexString("0x000101"), Bytes.EMPTY, "nonzero padding byte 0x01");
    }

    @Test
    void closeInCallDataTail() {
      assertRejected(
          Bytes.fromHexString("0x0001"),
          Bytes.fromHexString("0x01"),
          "CloseBlobStream in callData");
    }

    @Test
    void closeInCallDataOnlyStream() {
      assertThatThrownBy(() -> EezStreamDecoder.decode(List.of(), Bytes.fromHexString("0x0001")))
          .hasMessageContaining("CloseBlobStream in callData at offset 1");
    }

    @Test
    void truncatedCall() {
      assertRejected(Bytes.fromHexString("0x000401"), Bytes.EMPTY, "truncated Call.to_chain");
    }

    @Test
    void truncatedBytesField() {
      assertRejected(
          Bytes.fromHexString("0x00020700000000000000" + "05aabb"),
          Bytes.EMPTY,
          "truncated ChainOperation.operations");
    }

    @Test
    void truncatedVarint() {
      assertRejected(
          Bytes.fromHexString("0x00020700000000000000" + "80"),
          Bytes.EMPTY,
          "truncated varint ChainOperation.operations length");
    }

    @Test
    void truncatedMessageInCallDataTail() {
      assertRejected(
          Bytes.fromHexString("0x0001"), Bytes.fromHexString("0x0601"), "callData: truncated");
    }

    @Test
    void varintTooLong() {
      assertRejected(
          Bytes.fromHexString("0x00020700000000000000" + "808080808080"),
          Bytes.EMPTY,
          "exceeds 2^32-1");
    }
  }

  @Nested
  class BracketDiscipline {

    @Test
    void nestedTransaction() {
      assertRejected(
          List.of(initiate(L1, ""), initiate(L2A, "")), "nested cross-chain transaction");
    }

    @Test
    void callOutsideTransaction() {
      assertRejected(List.of(call(L2A, "")), "CALL at blob portion offset 1: outside");
    }

    @Test
    void staticCallOutsideTransaction() {
      assertRejected(List.of(staticCall(L2A, "")), "STATIC_CALL at blob portion offset 1: outside");
    }

    @Test
    void returnOutsideTransaction() {
      assertRejected(List.of(ok("")), "no outstanding call");
    }

    @Test
    void snapshotOutsideTransaction() {
      assertRejected(List.of(SNAPSHOT), "SNAPSHOT at blob portion offset 1: outside");
    }

    @Test
    void finishOutsideTransaction() {
      assertRejected(List.of(FINISH), "outside a cross-chain transaction");
    }

    @Test
    void chainOperationInsideTransaction() {
      assertRejected(List.of(initiate(L1, ""), chainOp(L2A, "ops")), "inside a cross-chain");
    }

    @Test
    void chainOperationBetweenCallAndReturn() {
      assertRejected(
          List.of(initiate(L1, ""), call(L2A, ""), chainOp(L2A, "ops")), "inside a cross-chain");
    }

    @Test
    void returnWithoutOpenCall() {
      assertRejected(List.of(initiate(L1, ""), ok("")), "no outstanding call");
    }

    @Test
    void revertWithUnreturnedCall() {
      assertRejected(
          List.of(initiate(L1, ""), SNAPSHOT, call(L2A, ""), REVERT),
          "a call opened after its Snapshot has not returned");
    }

    @Test
    void bareRevert() {
      assertRejected(List.of(initiate(L1, ""), REVERT), "no open Snapshot");
    }

    @Test
    void finishWithOpenCall() {
      assertRejected(List.of(initiate(L1, ""), call(L2A, ""), FINISH), "a call has not returned");
    }

    @Test
    void finishWithOpenSnapshot() {
      assertRejected(List.of(initiate(L1, ""), SNAPSHOT, FINISH), "a Snapshot is still open");
    }

    @Test
    void returnCrossesSnapshot() {
      assertRejected(
          List.of(initiate(L1, ""), call(L2A, ""), SNAPSHOT, ok("")),
          "returns a call opened before the innermost open Snapshot");
    }

    @Test
    void unclosedTransactionAtStreamEnd() {
      assertRejected(List.of(CLOSE, initiate(L1, "")), "stream ends inside an open cross-chain");
    }

    @Test
    void unclosedTransactionInBlobPortion() {
      assertRejected(List.of(initiate(L1, ""), CLOSE), "stream ends inside an open cross-chain");
    }

    @Test
    void unclosedCallDataOnlyStream() {
      final Bytes callData = EezStreamEncoder.encodeCallDataOnly(List.of(initiate(L1, "")));
      assertThatThrownBy(() -> EezStreamDecoder.decode(List.of(), callData))
          .hasMessageContaining("stream ends inside an open cross-chain");
    }

    @Test
    void deepNestingHasNoArbitraryLimit() {
      final List<EezMessage> messages = new ArrayList<>();
      messages.add(initiate(L1, ""));
      for (int i = 0; i < 200; i++) {
        messages.add(call(i % 2 == 0 ? L2A : L1, ""));
      }
      for (int i = 0; i < 200; i++) {
        messages.add(ok(""));
      }
      messages.add(FINISH);
      messages.add(CLOSE);
      assertThat(roundTripThroughBlobs(messages)).isEqualTo(messages);
    }
  }
}
