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
package eez.besu.follower.chain;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.crypto.SignatureAlgorithmFactory;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.TransactionType;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.core.InMemoryKeyValueStorageProvider;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.TransactionTestFixture;
import org.hyperledger.besu.ethereum.core.encoding.EezHistoryTransactionEncoder;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.hyperledger.besu.plugin.services.worldstate.MutableWorldState;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;

class RollupXBlockProcessorTest {

  private static final BigInteger CHAIN_ID = BigInteger.valueOf(699169);

  private static final Transaction HISTORY =
      Transaction.builder()
          .type(TransactionType.EEZ_HISTORY)
          .chainId(CHAIN_ID)
          .gasLimit(EezHistoryTransactionEncoder.GAS_LIMIT)
          .to(EezHistoryTransactionEncoder.HISTORY_ADDRESS)
          .value(Wei.ZERO)
          .payload(
              EezHistoryTransactionEncoder.importCalldata(
                  List.of(new EezHistoryTransactionEncoder.L1Block(1, Bytes32.repeat((byte) 1)))))
          .build();
  private static final Transaction LOAD =
      system(RollupXChainConfig.EEZ_L2_ADDRESS, RollupXChainConfig.LOAD_EXECUTION_TABLE_SELECTOR);
  private static final Transaction INCOMING =
      system(RollupXChainConfig.EEZ_L2_ADDRESS, RollupXChainConfig.EXECUTE_INCOMING_SELECTOR);
  private static final Transaction USER =
      new TransactionTestFixture()
          .type(TransactionType.EIP1559)
          .chainId(Optional.of(CHAIN_ID))
          .createTransaction(SignatureAlgorithmFactory.getInstance().generateKeyPair());

  @Test
  void acceptsTheShapesTheReferenceClientProduces() {
    assertThat(RollupXBlockProcessor.structuralViolation(List.of())).isEmpty();
    assertThat(RollupXBlockProcessor.structuralViolation(List.of(HISTORY, USER, INCOMING)))
        .isEmpty();
    assertThat(RollupXBlockProcessor.structuralViolation(List.of(HISTORY, LOAD, USER, USER)))
        .isEmpty();
    assertThat(RollupXBlockProcessor.structuralViolation(List.of(INCOMING, LOAD, USER))).isEmpty();
  }

  @Test
  void historyOnlyOpensTheBlock() {
    assertThat(RollupXBlockProcessor.structuralViolation(List.of(USER, HISTORY))).isPresent();
    assertThat(RollupXBlockProcessor.structuralViolation(List.of(HISTORY, HISTORY))).isPresent();
  }

  @Test
  void aLoadIsFollowedByItsUserTransaction() {
    assertThat(RollupXBlockProcessor.structuralViolation(List.of(LOAD))).isPresent();
    assertThat(RollupXBlockProcessor.structuralViolation(List.of(USER, LOAD))).isPresent();
    assertThat(RollupXBlockProcessor.structuralViolation(List.of(LOAD, INCOMING, USER)))
        .isPresent();
    assertThat(RollupXBlockProcessor.structuralViolation(List.of(LOAD, LOAD, USER))).isPresent();
  }

  @Test
  void inboundTransactionsCallEezL2WithASupportedFunction() {
    assertThat(
            RollupXBlockProcessor.structuralViolation(
                List.of(
                    system(
                        Address.fromHexString("0xee50000000000000000000000000000000000000"),
                        RollupXChainConfig.EXECUTE_INCOMING_SELECTOR))))
        .isPresent();
    assertThat(
            RollupXBlockProcessor.structuralViolation(
                List.of(
                    system(RollupXChainConfig.EEZ_L2_ADDRESS, Bytes.fromHexString("0x12345678")))))
        .isPresent();
  }

  @Test
  void groupsCloseOnAnIncomingCallOrTheTransactionAfterALoad() {
    final List<Transaction> block = List.of(HISTORY, INCOMING, LOAD, USER, USER);

    assertThat(RollupXBlockProcessor.completesGroup(block, 0)).isFalse();
    assertThat(RollupXBlockProcessor.completesGroup(block, 1)).isTrue();
    assertThat(RollupXBlockProcessor.completesGroup(block, 2)).isFalse();
    assertThat(RollupXBlockProcessor.completesGroup(block, 3)).isTrue();
    assertThat(RollupXBlockProcessor.completesGroup(block, 4)).isFalse();
  }

  @Test
  void clearingBurnsProtocolBalancesAndRemovesAnEmptiedSystemAccount() {
    final MutableWorldState worldState = InMemoryKeyValueStorageProvider.createInMemoryWorldState();
    final Address other = Address.fromHexString("0x1234000000000000000000000000000000000000");
    final WorldUpdater setup = worldState.updater();
    setup.getOrCreate(RollupXChainConfig.EEZ_L2_ADDRESS).setCode(Bytes.of(0x00));
    setup.getAccount(RollupXChainConfig.EEZ_L2_ADDRESS).setBalance(Wei.of(5));
    setup.getOrCreate(Transaction.PROTOCOL_DERIVED_SENDER).setBalance(Wei.of(3));
    setup.getOrCreate(other).setBalance(Wei.of(7));
    setup.commit();

    final WorldUpdater updater = worldState.updater();
    ProtocolBalances.clear(updater);
    updater.commit();

    assertThat(worldState.get(RollupXChainConfig.EEZ_L2_ADDRESS).getBalance()).isEqualTo(Wei.ZERO);
    assertThat(worldState.get(RollupXChainConfig.EEZ_L2_ADDRESS).getCode()).isEqualTo(Bytes.of(0));
    assertThat(worldState.get(Transaction.PROTOCOL_DERIVED_SENDER)).isNull();
    assertThat(worldState.get(other).getBalance()).isEqualTo(Wei.of(7));
  }

  private static Transaction system(final Address to, final Bytes selector) {
    return Transaction.builder()
        .type(TransactionType.EEZ_INBOUND)
        .chainId(CHAIN_ID)
        .sourceHash(Bytes32.repeat((byte) 0x11))
        .gasLimit(RollupXChainConfig.SYSTEM_TX_GAS_CAP)
        .to(to)
        .value(Wei.ZERO)
        .payload(Bytes.concatenate(selector, Bytes32.ZERO))
        .build();
  }
}
