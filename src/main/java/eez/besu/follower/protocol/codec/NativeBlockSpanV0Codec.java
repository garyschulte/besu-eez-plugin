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

import org.hyperledger.besu.datatypes.Address;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

import org.apache.tuweni.bytes.Bytes;

/**
 * Codec for the Rollup0 payload carried in {@code ChainOperation.operations}, payload version
 * {@code 0x00} (Rollup0 D.4.2, D.4.3):
 *
 * <pre>
 * 0x00
 * || uvarint32(block_count)
 * || uvarint32(pure_transaction_counts[i])                  for each block
 * || (uvarint32(run_length) || beneficiary[20])*            maximal runs summing to block_count
 * || (uvarint32(run_length) || u8(len) || extra_data[len])* maximal runs summing to block_count
 * || uvarint32(pure_transaction_lengths[i])                 for each of T transactions
 * || pure_transactions[i]                                   for each of T transactions
 * </pre>
 *
 * <p>The block-count schedule check (a positive multiple of the Sync interval) is candidate
 * validity rather than codec validity, so it is only applied when a positions-per-interval value is
 * given.
 */
public final class NativeBlockSpanV0Codec {

  public static final int PAYLOAD_VERSION = 0x00;

  /** Production Rollup0 positions per Sync interval (D.4.3). Dev networks override (A.2). */
  public static final int PRODUCTION_POSITIONS_PER_INTERVAL = 6;

  private static final String CONTEXT = "Rollup0 V0 operations";

  private NativeBlockSpanV0Codec() {}

  /**
   * Decodes {@code operations} without the schedule check.
   *
   * @throws PayloadDecodingException if the bytes are not a valid V0 payload
   */
  public static NativeBlockSpan decode(final Bytes operations) {
    return decode(operations, 0);
  }

  /**
   * Decodes {@code operations}, checking {@code block_count} against the Sync schedule right after
   * the early bound, as D.4.3 orders it.
   *
   * @param operations the exact {@code ChainOperation.operations} bytes
   * @param positionsPerInterval the Sync interval length, or {@code 0} to skip the schedule check
   * @throws PayloadDecodingException if the bytes are not a valid V0 payload or the block count is
   *     off-schedule
   */
  public static NativeBlockSpan decode(final Bytes operations, final int positionsPerInterval) {
    final ByteReader reader = new ByteReader(operations, CONTEXT);
    if (!reader.hasRemaining()) {
      throw new PayloadDecodingException(CONTEXT + ": empty, payload version byte is absent");
    }
    final int version = reader.readUnsignedByte();
    if (version != PAYLOAD_VERSION) {
      throw new PayloadDecodingException(
          String.format("%s: unknown payload version 0x%02x", CONTEXT, version));
    }

    final long blockCount = Uvarint32.readCanonical(reader, "block_count");
    if (blockCount == 0) {
      throw new PayloadDecodingException(CONTEXT + ": block_count is zero");
    }
    if (blockCount > reader.remaining()) {
      throw new PayloadDecodingException(
          String.format(
              "%s: block_count %d exceeds the %d remaining bytes",
              CONTEXT, blockCount, reader.remaining()));
    }
    if (positionsPerInterval > 0) {
      requireOnSchedule(blockCount, positionsPerInterval);
    }
    final int blocks = (int) blockCount;

    final long[] transactionCounts = new long[blocks];
    long totalTransactions = 0;
    for (int i = 0; i < blocks; i++) {
      final long count = Uvarint32.readCanonical(reader, "pure_transaction_counts[" + i + "]");
      transactionCounts[i] = count;
      totalTransactions = Math.addExact(totalTransactions, count);
    }

    final List<Address> beneficiaries =
        readRuns(
            reader,
            blocks,
            "beneficiary",
            r -> Address.wrap(r.readBytes(Address.SIZE, "beneficiary")));
    final List<Bytes> extraData =
        readRuns(reader, blocks, "extraData", NativeBlockSpanV0Codec::readExtraData);

    if (totalTransactions > reader.remaining() / 2) {
      throw new PayloadDecodingException(
          String.format(
              "%s: %d transactions cannot fit in the %d remaining bytes",
              CONTEXT, totalTransactions, reader.remaining()));
    }
    final int transactionTotal = (int) totalTransactions;
    final long[] lengths = new long[transactionTotal];
    long totalLength = 0;
    for (int i = 0; i < transactionTotal; i++) {
      lengths[i] = Uvarint32.readCanonical(reader, "pure_transaction_lengths[" + i + "]");
      if (lengths[i] == 0) {
        throw new PayloadDecodingException(
            String.format("%s: pure_transaction_lengths[%d] is zero", CONTEXT, i));
      }
      totalLength = Math.addExact(totalLength, lengths[i]);
    }
    if (totalLength != reader.remaining()) {
      throw new PayloadDecodingException(
          String.format(
              "%s: transaction lengths sum to %d but the transaction column has %d bytes (%s)",
              CONTEXT,
              totalLength,
              reader.remaining(),
              totalLength > reader.remaining() ? "truncated" : "trailing bytes"));
    }

    final List<NativeBlock> result = new ArrayList<>(blocks);
    int transactionIndex = 0;
    for (int i = 0; i < blocks; i++) {
      // every count is bounded by the transaction total checked above
      final List<Bytes> transactions = new ArrayList<>((int) transactionCounts[i]);
      for (int t = 0; t < transactionCounts[i]; t++) {
        transactions.add(reader.readBytes(lengths[transactionIndex++], "pure transaction"));
      }
      result.add(new NativeBlock(beneficiaries.get(i), extraData.get(i), transactions));
    }
    return new NativeBlockSpan(result);
  }

  /**
   * Checks the production schedule rule: the span must cover whole Sync intervals.
   *
   * @param blockCount the decoded {@code block_count}
   * @param positionsPerInterval positions per Sync interval ({@link
   *     #PRODUCTION_POSITIONS_PER_INTERVAL} in production)
   * @throws PayloadDecodingException if {@code blockCount} is not a positive multiple
   */
  public static void requireOnSchedule(final long blockCount, final int positionsPerInterval) {
    if (positionsPerInterval <= 0) {
      throw new IllegalArgumentException("positionsPerInterval must be positive");
    }
    if (blockCount <= 0 || blockCount % positionsPerInterval != 0) {
      throw new PayloadDecodingException(
          String.format(
              "%s: block_count %d is not a positive multiple of %d",
              CONTEXT, blockCount, positionsPerInterval));
    }
  }

  private static Bytes readExtraData(final ByteReader reader) {
    final int length = reader.readUnsignedByte();
    if (length > NativeBlock.MAX_EXTRA_DATA_SIZE) {
      throw new PayloadDecodingException(
          String.format("%s: extraData length %d exceeds 32", CONTEXT, length));
    }
    return reader.readBytes(length, "extraData");
  }

  /** Reads maximal runs of {@code value} whose lengths sum to exactly {@code blocks}. */
  private static <T> List<T> readRuns(
      final ByteReader reader,
      final int blocks,
      final String column,
      final Function<ByteReader, T> valueReader) {
    final List<T> expanded = new ArrayList<>(blocks);
    T previous = null;
    int run = 0;
    while (expanded.size() < blocks) {
      final long length = Uvarint32.readCanonical(reader, column + " run length " + run);
      if (length == 0) {
        throw new PayloadDecodingException(
            String.format("%s: %s run %d has zero length", CONTEXT, column, run));
      }
      if (length > blocks - expanded.size()) {
        throw new PayloadDecodingException(
            String.format(
                "%s: %s run %d of length %d overshoots block_count %d",
                CONTEXT, column, run, length, blocks));
      }
      final T value = valueReader.apply(reader);
      if (Objects.equals(previous, value)) {
        throw new PayloadDecodingException(
            String.format(
                "%s: %s run %d repeats the previous run's value (runs must be maximal)",
                CONTEXT, column, run));
      }
      for (long i = 0; i < length; i++) {
        expanded.add(value);
      }
      previous = value;
      run++;
    }
    return expanded;
  }

  /**
   * Encodes a span in the V0 format with maximal metadata runs.
   *
   * @throws IllegalArgumentException if the span is empty or holds an empty transaction
   */
  public static Bytes encode(final NativeBlockSpan span) {
    final List<NativeBlock> blocks = span.blocks();
    if (blocks.isEmpty()) {
      throw new IllegalArgumentException("a V0 span needs at least one block");
    }
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(PAYLOAD_VERSION);
    Uvarint32.write(blocks.size(), out);
    blocks.forEach(block -> Uvarint32.write(block.transactions().size(), out));
    writeRuns(
        blocks,
        NativeBlock::beneficiary,
        beneficiary -> out.writeBytes(beneficiary.getBytes().toArrayUnsafe()),
        out);
    writeRuns(
        blocks,
        NativeBlock::extraData,
        extraData -> {
          out.write(extraData.size());
          out.writeBytes(extraData.toArrayUnsafe());
        },
        out);
    final List<Bytes> transactions =
        blocks.stream().flatMap(block -> block.transactions().stream()).toList();
    for (final Bytes transaction : transactions) {
      if (transaction.isEmpty()) {
        throw new IllegalArgumentException("pure transactions must not be empty");
      }
      Uvarint32.write(transaction.size(), out);
    }
    transactions.forEach(transaction -> out.writeBytes(transaction.toArrayUnsafe()));
    return Bytes.wrap(out.toByteArray());
  }

  private static <T> void writeRuns(
      final List<NativeBlock> blocks,
      final Function<NativeBlock, T> field,
      final Consumer<T> valueWriter,
      final ByteArrayOutputStream out) {
    int start = 0;
    while (start < blocks.size()) {
      final T value = field.apply(blocks.get(start));
      int end = start + 1;
      while (end < blocks.size() && field.apply(blocks.get(end)).equals(value)) {
        end++;
      }
      Uvarint32.write(end - start, out);
      valueWriter.accept(value);
      start = end;
    }
  }
}
