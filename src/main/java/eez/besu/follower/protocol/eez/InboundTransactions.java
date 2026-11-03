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

import eez.besu.follower.protocol.eez.abi.AbiFunction;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.TransactionType;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.encoding.EncodingContext;
import org.hyperledger.besu.ethereum.core.encoding.TransactionEncoder;

import java.math.BigInteger;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Reconstructs the type-0x45 inbound protocol transaction of a successful action (D.5, F.3, G.5).
 *
 * <p>The {@code input} is {@code EEZL2.executeIncomingCrossChainCall(ExecutionEntry[],
 * StaticExecutionEntryL2[])} at eez-core rev 84a5b52, with exactly one entry and no static entries
 * (ch. 5): {@code incomingCalls = [(0, false, gas, source, 0, target, value, data)]}, no expected
 * outgoing calls, {@code success = true}, and the {@code ReturnSuccess} data as both the call
 * result and the entry return data.
 */
public final class InboundTransactions {

  /** {@code EEZL2.executeIncomingCrossChainCall(ExecutionEntry[],StaticExecutionEntryL2[])}. */
  public static final AbiFunction EXECUTE_INCOMING_CROSS_CHAIN_CALL =
      AbiFunction.parse(
          "executeIncomingCrossChainCall("
              + L2ExecutionEntry.TYPE.canonical()
              + "[],"
              + L2StaticExecutionEntry.TYPE.canonical()
              + "[])");

  private InboundTransactions() {}

  /**
   * The decoded arguments of {@code executeIncomingCrossChainCall}.
   *
   * @param entries the execution table; {@code entries[0]} is delivered
   * @param staticEntries the static table
   */
  public record IncomingCall(
      List<L2ExecutionEntry> entries, List<L2StaticExecutionEntry> staticEntries) {

    /** Copies the lists. */
    public IncomingCall {
      entries = List.copyOf(entries);
      staticEntries = List.copyOf(staticEntries);
    }
  }

  /**
   * The gas limit of an inbound transaction: {@code min(remainingBlockGas, 16_777_216)} (D.5).
   *
   * @param remainingBlockGas block gas remaining before the transaction starts
   * @return the gas limit
   */
  public static long gasLimit(final long remainingBlockGas) {
    if (remainingBlockGas < 0) {
      throw new IllegalArgumentException("Negative remaining block gas: " + remainingBlockGas);
    }
    return Math.min(remainingBlockGas, Rollup0Constants.INBOUND_GAS_CAP);
  }

  /**
   * The single {@code EEZL2} execution entry delivered for an action.
   *
   * <p>{@code proxyEntryHash} folds {@code incomingCalls[0].gas} because {@code
   * executeIncomingCrossChainCall} checks it that way; it equals the L1 call hash when the bracket
   * gas is zero. The {@code CALL_BEGIN} fold always uses {@code callGas = 0}.
   *
   * @param action the successful action
   * @param rollup0EezRollupId Rollup0's EEZ rollup id ({@code EEZL2.ROLLUP_ID})
   * @return the entry
   */
  public static L2ExecutionEntry l2Entry(
      final InboundAction action, final long rollup0EezRollupId) {
    final long source = Rollup0Constants.SETTLEMENT_L1_ROLLUP_ID;
    final CrossChainCall inbound =
        new CrossChainCall(
            0,
            false,
            action.gas(),
            action.sourceAddress(),
            source,
            action.targetAddress(),
            action.value(),
            action.data());
    final Bytes32 proxyEntryHash =
        EezHashes.crossChainCallHash(
            false,
            action.sourceAddress(),
            source,
            action.targetAddress(),
            rollup0EezRollupId,
            action.value(),
            action.gas(),
            action.data());
    final Bytes32 rollingHash =
        EezHashes.callEnd(
            EezHashes.callBegin(
                EezHashes.l2EntrySeed(proxyEntryHash), action.callHash(rollup0EezRollupId)),
            true,
            action.returnData());
    return new L2ExecutionEntry(
        proxyEntryHash, List.of(inbound), List.of(), rollingHash, true, action.returnData());
  }

  /**
   * The exact {@code executeIncomingCrossChainCall} calldata delivering one entry.
   *
   * @param entry the entry to deliver
   * @return selector and ABI-encoded arguments
   */
  public static Bytes input(final L2ExecutionEntry entry) {
    return EXECUTE_INCOMING_CROSS_CHAIN_CALL.encodeCall(List.of(List.of(entry.toAbi()), List.of()));
  }

  /**
   * Decodes {@code executeIncomingCrossChainCall} calldata, e.g. from a received 0x45 transaction.
   *
   * @param input the calldata
   * @return the decoded tables
   * @throws eez.besu.follower.protocol.eez.abi.AbiDecodingException if the input is malformed
   */
  public static IncomingCall decodeInput(final Bytes input) {
    final List<Object> args = EXECUTE_INCOMING_CROSS_CHAIN_CALL.decodeCall(input);
    return new IncomingCall(
        AbiValues.listOf(args.get(0), L2ExecutionEntry::fromAbi),
        AbiValues.listOf(args.get(1), L2StaticExecutionEntry::fromAbi));
  }

  /**
   * Builds the type-0x45 transaction from its envelope fields (F.3).
   *
   * @param rollup0ChainId the Rollup0 chain id
   * @param sourceHash the {@link InboundSource#sourceHash source hash}
   * @param gasLimit the {@link #gasLimit(long) gas limit}
   * @param eezL2 the {@code EEZL2} predeploy
   * @param value the action value
   * @param input the {@link #input(L2ExecutionEntry) calldata}
   * @return the unsigned protocol transaction, sender {@code SYSTEM_ADDRESS}
   */
  public static Transaction transaction(
      final BigInteger rollup0ChainId,
      final Bytes32 sourceHash,
      final long gasLimit,
      final Address eezL2,
      final Wei value,
      final Bytes input) {
    if (gasLimit < 0 || gasLimit > Rollup0Constants.INBOUND_GAS_CAP) {
      throw new IllegalArgumentException("Inbound gas limit out of range: " + gasLimit);
    }
    return Transaction.builder()
        .type(TransactionType.EEZ_INBOUND)
        .chainId(rollup0ChainId)
        .sourceHash(sourceHash)
        .gasLimit(gasLimit)
        .to(eezL2)
        .value(value)
        .payload(input)
        .build();
  }

  /**
   * Derives the complete type-0x45 transaction for a successful action (G.5).
   *
   * @param deployment the configured Rollup0 identities
   * @param settlement the authenticated settlement context
   * @param action the successful action
   * @param remainingBlockGas block gas remaining before the transaction starts
   * @return the protocol transaction
   */
  public static Transaction derive(
      final Rollup0Deployment deployment,
      final SettlementContext settlement,
      final InboundAction action,
      final long remainingBlockGas) {
    final Bytes32 sourceHash =
        InboundSource.sourceHash(
            deployment.sourceDomain(),
            settlement,
            action.manifestIndex(),
            action.callHash(deployment.eezRollupId()));
    return transaction(
        deployment.rollup0ChainId(),
        sourceHash,
        gasLimit(remainingBlockGas),
        deployment.eezL2(),
        Wei.of(action.value()),
        input(l2Entry(action, deployment.eezRollupId())));
  }

  /**
   * The canonical {@code 0x45 || rlp(...)} bytes; their keccak is the transaction hash.
   *
   * @param transaction a type-0x45 transaction
   * @return the typed envelope
   */
  public static Bytes envelope(final Transaction transaction) {
    return TransactionEncoder.encodeOpaqueBytes(transaction, EncodingContext.BLOCK_BODY);
  }
}
