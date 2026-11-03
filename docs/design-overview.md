# besu-eez-plugin: design overview

Status: implemented (first cut), 2026-11-03. Sections 5, 8 and 9 describe what was built.
Section 6.1 covers the rollupX execution profile, added to follow the live EEZ-X testnet.

This plugin turns Besu into an **EEZ follower**: a node that rebuilds an EEZ L2 chain from
Ethereum data alone and serves it over standard RPC. It does not sequence, compose, prove, or
submit anything.

## 1. Sources and how we use them

| Source | Revision | Role |
|---|---|---|
| [specs-draft](https://github.com/eez-association/specs-draft), Rollup0 spec | `2c6d346` (2026-10-06) | **Normative.** The follower implements this. |
| [eez-rollupX](https://github.com/eez-association/eez-rollupX) | `c702f90` (2026-10-29) | **Informative.** A working Rust implementation on reth. We read it to understand behavior and to produce test data. We do not port its divergences from the draft (section 6). |
| Besu | `f13ef3bcab` plus the changes in section 5 | The execution client we extend. |

No Rust code from rollupX is reusable. Its EEZ logic is written against reth's in-process
interfaces (engine handles, EVM config, state providers), not against a wire protocol. What we
reuse is:

- the draft spec's rules and its byte-level vectors (Appendix D.4.4, `companion-docs/` wire vectors);
- rollupX's code as a reading aid where the spec is terse;
- real chain data produced by rollupX (blobs, L1 logs, L2 blocks) as differential test fixtures,
  wherever it agrees with the draft.

## 2. Principles

1. **Least change in Besu.** Extend through the plugin. Change Besu core only where a plugin
   cannot reach.
2. **Drive Besu the way a consensus client does.** Block import, head, safe, finalized, and reorgs
   go through the Engine API (`engine_newPayload`, `engine_forkchoiceUpdated`). No new import or
   fork-choice API.
3. **One process.** The consensus logic (a Java "CL") runs inside the plugin, in the Besu JVM.
4. **Follow the draft spec for derivation.** Where rollupX differs, the draft wins, with one
   exception: the plugin also implements rollupX's execution rules (section 6.1), because they are
   what the live network runs and the only thing we can test against real data.

## 3. What EEZ on reth is today (rollupX)

rollupX runs **stock reth v2.7.0** with EEZ logic layered on through reth's extension traits, plus
three vendored reth crates. Its crates fall into four groups.

### 3.1 The reth extensions (chain rules)

These change what a valid block is. A follower must reproduce all of them exactly.

| Crate | What it changes in reth |
|---|---|
| `eez-primitives` | Adds EEZ's unsigned native transaction types and receipts to reth's primitive types, and decodes them in Engine API payloads. rollupX defines `0x45` (cross-chain delivery) and `0x46` (L1 history). |
| `eez-evm` | Wraps reth's `EthEvmConfig`. Native transactions run nonce-free with zero fee, use the remaining block gas, and credit the delivered value inside a state checkpoint. The base fee goes to a fee account instead of being burned. The block lifecycle keeps the EIP-2935 history write and drops the EIP-4788 beacon-root call and the EIP-7002/7251 request calls. Also: fixed L2 rules applied over the genesis document (`profile`), and balance cleanup and closed-prefix roots used by the producer. |
| vendored `reth-engine-tree`, `reth-node-builder`, `reth-rpc-builder` | Operational patches only: clean shutdown (from open upstream PR #27609) and an RPC guard. No chain-rule effect. |

### 3.2 The driver (consensus logic)

`eez-driver` is the "consensus driver" half of the node, in the same role as op-node. It talks to
reth only through in-process engine handles.

| Module | Role | Needed by a follower? |
|---|---|---|
| `block_committer` | One actor that serializes every engine call (newPayload, forkchoiceUpdated) and holds the unsafe, safe, and finalized cursors. | Yes, as a role. |
| `derivation` | Rebuilds a block's environment from its parent plus the published metadata (timestamp, fee recipient, `extraData`, `prevRandao`, fixed gas limit, zero beacon root, empty withdrawals), then requires the rebuilt block to match the original byte for byte. | Yes. This is the core recipe. |
| `sequencer`, `slot`, `timing` | Produce blocks on the 2 s grid, mark Live and Sync positions, schedule settlement work. | No. Producer only. |
| `witness`, `observation` | Feed the prover and replay static calls for the composer and signer. | No. |

### 3.3 The follower orchestration

`eez-deriver` reads L1 settlement events, decodes batches, executes each block locally to fill in
the execution results (state root, receipts, block hash), submits it through the block committer,
moves safe and finalized, and retreats on L1 reorgs. `eez-follower` is the launcher.

### 3.4 Supporting crates

`eez-l1` (L1 client and settlement events), `eez-protocol` (EEZ ABI, call hashes, entries,
canonical system-transaction construction), `eez-payload-codec` and `eez-blob-codec` (the published
data format). Everything else (`eez-composer`, `eez-proof-signer`, `eez-prover*`, `eez-control-rpc`,
the ingress fronts) is producer or settlement infrastructure.

## 4. How the Besu plugin maps onto that

| rollupX piece | Besu equivalent | Where |
|---|---|---|
| `eez-primitives`: native type `0x45` | New `0x45` transaction type: codec, typed receipt, execution semantics. Not accepted by any mainnet fork. | Besu core (section 5.2) |
| `eez-primitives`: native type `0x46` | New `0x46` transaction type: codec, execution as a call to the history predeploy. Not accepted by any mainnet fork. | Besu core (5.2), rules in the plugin (6.1) |
| `eez-evm`: fee market (denominator 50) | A `BaseFeeMarket` with elasticity 2 and change denominator 50. | Plugin, via the customizer hook |
| `eez-evm`: base fee to a fee account | Credit `baseFee × gasUsed` to `FEE_COLLECTOR` instead of burning it (draft ch. 11). | Core hook (5.3), selected by the plugin's fee market |
| `eez-evm`: no beacon-root or request calls | Pre-execution processor with EIP-2935 only, and an empty request processor set. | Plugin, via the customizer hook |
| `eez-evm`: `profile` (fixed rules over genesis) | An `eez` section in the genesis file, read through `GenesisConfigOptions.getCustomSection("eez")`. | Plugin, plus a small core accessor (5.4) |
| vendored reth patches | Not applicable. | — |
| `eez-driver::block_committer` | `EngineDriver`: calls Besu's Engine API, holds the head, safe, and finalized cursors. | Plugin |
| `eez-driver::derivation` | `BlockRebuilder`: the same recipe, following draft ch. 4 and ch. 10. | Plugin |
| `eez-deriver` | `Deriver`: L1 watching, batch handling, reorg handling, following draft ch. 10. | Plugin |
| local execution before newPayload | `testing_buildBlockV1`, called in-process through `RpcEndpointService.call`. | Existing Besu capability, no change |
| `eez-l1`, `eez-protocol` | Java L1 JSON-RPC and beacon clients, EEZ ABI types, call hash, `sourceHash`. | Plugin |
| `eez-payload-codec`, `eez-blob-codec` | Java V0 payload decoder, checked against Appendix D.4.4 vectors. | Plugin |
| sequencer, composer, signer, fronts | Not needed. | — |

## 5. Changes to Besu core

Four, all generic, on branch `eez-follower` of `~/dev/besu`.

### 5.1 Protocol-schedule customizer hook

diega's [`pr/protocol-schedule-customization`](https://github.com/diega/besu/tree/pr/protocol-schedule-customization)
(written for Ethereum Classic), cherry-picked onto main with its tests ported to main's
`blockRewardProcessor` API. During `register()`, the plugin obtains `ProtocolScheduleService` and
registers a `ProtocolScheduleCustomizer`, which returns `ProtocolSpecModification`s over
`ProtocolSpecBuilder`. The hook lives in `ethereum/core` as `@Unstable`.

### 5.2 Transaction types `0x45` and `0x46`

The wire forms are rollupX's, which the live network uses. The draft's `0x45` (Appendix F.3) adds
a leading `version` field; nothing produces it, so Besu does not accept it.

```text
0x45 || rlp([chainId, sourceHash, gasLimit, to, value, input])
0x46 || rlp([version = 1, chainId, [[number, blockHash], ...]])
receipt = type || rlp([status, cumulativeGasUsed, logsBloom, logs])
```

- `TransactionType.EEZ_INBOUND` and `EEZ_HISTORY`, both `isProtocolDerived()`. No mainnet fork
  accepts either; the plugin adds them to the accepted set of an EEZ chain only.
- `0x45` codec (`EezInboundTransactionEncoder/Decoder`): exactly six fields.
- `0x46` codec (`EezHistoryTransactionEncoder/Decoder`): 1 to 64 blocks, numbers strictly
  increasing, no zero number or hash. The envelope has no destination, gas, value or calldata; the
  decoder fills in the fixed ones (`0xeeee…0077`, 2,000,000 gas, zero value) and the derived
  calldata `importL1Blocks((uint64,bytes32)[])`, so the decoded transaction is the call it executes.
  The encoder recovers the block list from that calldata. Checked against a live transaction.
- `Transaction`: unsigned, `sourceHash` field, implicit sender `0xff…fe`; `r`, `s`, `v`, `yParity`
  report zero.
- Validation: chain id must match, `to` present, gas limit at most 2^24 regardless of fork; no
  signature, fee, balance or nonce checks. The transaction pool and `eth_sendRawTransaction`
  reject it; the base-fee block-body check skips it.
- Execution: no nonce change, gas price zero, the delivered value credited to the sender inside
  the transaction's own updater, nothing paid to the beneficiary or a fee collector. A failed
  protocol transaction gets an ordinary failed receipt and its credit is undone. Whether a failure
  is allowed is a chain rule, so it lives in the plugin: the draft profile makes the block
  invalid, rollupX keeps the receipt.
- JSON-RPC: zero placeholders and empty lists, `sourceHash` on `0x45`, `version` on `0x46`.
- `transactionTypeByEthSerializedType` was sized by enum count and indexed by type byte; it is now
  sized for every EIP-2718 type byte.

### 5.3 Base-fee collector

`FeeMarket.getBaseFeeCollector()`, empty by default. When present, the transaction processor
credits it with `min(baseFee, gasPrice) × usedGas` plus the blob fee after each transaction, which
is the Gnosis Chain rule the draft adopts (ch. 11). `BlobFeeMarket` and `LondonFeeMarket`
constructors became `protected` so the plugin can subclass them.

### 5.4 Custom genesis sections

`GenesisConfigOptions.getCustomSection(name)` returns a top-level `config` section Besu does not
interpret, with keys lower-cased. (`asMap()`, which this document previously named, is a curated
map of known keys and does not expose such sections.)

## 6. Where rollupX differs from the draft

| Topic | Draft | rollupX |
|---|---|---|
| `0x45` envelope | 7 fields, including `version = 0` | 6 fields, no version (Besu implements this) |
| History transaction | None | `0x46` and an L1 history predeploy |
| `prevRandao` | Copied from the latest L1 block strictly before each 12 s interval (ch. 4.2) | Taken from the last imported L1 block, inherited until the next import |
| Failed `0x45` | Block invalid | Failed receipt, block valid |
| Protocol balances | Untouched | `EEZL2` and the system sender are zeroed after each cross-chain group and at block end |
| Fee destination | `FEE_COLLECTOR`, a genesis parameter | FeeVault predeploy (`0xeeee…0078`), named by the genesis |
| `executeIncomingCrossChainCall` | `(sourceAddress, sourceRollup, entry, lookups, value)` | eez-core `84a5b52`: `(ExecutionEntry[], StaticExecutionEntryL2[])` |
| `EEZL2` address | `0xee50000000000000000000000000000000000000` | `0x4B2760F84B83d6b3829E30A7573B0172B6d6402C` |
| Cadence | 2 s blocks, 6 per interval | Genesis parameters; the live network uses 1 s and 5 |
| Settlement network | Ethereum | Gnosis Chiado (testnet) |

### 6.1 The rollupX execution profile

The genesis section selects the profile: `eez` for the draft, `rollupX` for the reference client's
rules. Both share the fee market, the fee collector, EIP-2935 without EIP-4788, and no requests.
rollupX adds, all in the plugin:

- `0x46` in the accepted types;
- `RollupXBlockProcessor`, which checks the block's shape before execution (a `0x46` only opens
  the block; a `0x45` calls `EEZL2` with `loadExecutionTable` or `executeIncomingCrossChainCall`;
  a table load is followed directly by an ordinary transaction and cannot end the block), requires
  each `0x45` gas limit to equal the block's remaining gas capped at 2^24, requires a `0x46` to
  succeed with no output or logs, and zeroes the protocol balances after each completed group;
- `RollupXBlockRewardProcessor`, which zeroes them again when the block closes. It runs in block
  building as well as import.

Not enforced: that a `0x46` import and the block's `prevRandao` agree with Chiado. That needs L1
headers, and belongs with derivation.

**Live result.** The live genesis is not published, but it can be rebuilt: the rollupX repo's
`genesis.json` template has the same ten predeploys, and their block-0 code, nonces and storage are
readable over the public RPC. The rebuilt file (`genesis/rollupx-chiado.json`) reproduces the live
genesis hash `0x283b471b…`. Replaying the live chain through `engine_newPayloadV4` into Besu with
the plugin, every block so far is VALID with matching state and receipt roots (section 9).

rollupX settlement derivation is not implemented; the draft deriver does not apply to it.

## 7. The follower loop

Per draft ch. 10.2, for each Ethereum block in canonical order:

1. Read `BatchPosted`, `ExecutionConsumed`, and `L2ExecutionPerformed` from the configured EEZ
   address and rollup ID.
2. Fetch the referenced blobs from a beacon node and verify each KZG commitment and versioned hash.
3. Decode the V0 payload: block boundaries, beneficiaries, `extraData`, pure-L2 transactions, and
   the action manifest.
4. Check that the first block's parent is the current EEZ commitment.
5. For each block: derive the environment (parent + 2 s, fixed gas limit, `prevRandao` from L1,
   published beneficiary and `extraData`), add the pure-L2 transactions, then rebuild one `0x45`
   transaction per successful action.
6. Have Besu build the block from that exact list, compare its hash with the L1 commitment, then
   `engine_newPayload` and `engine_forkchoiceUpdated` (head).
7. Move safe on settlement and finalized when the L1 block finalizes. On an L1 reorg, retreat safe
   to the last surviving endpoint. On any mismatch, halt.

## 8. Open items

Resolved:

- **Building a block from a given list:** `testing_buildBlockV1` in-process, with
  `--Xin-process-rpc-enabled --Xin-process-rpc-apis=ENGINE,TESTING,ETH`. No core change.
- **EIP-4788:** the customizer installs `EezPreExecutionProcessor`, which makes only the EIP-2935
  write.

Still open:

1. **Spec vs Solidity on `executeIncomingCrossChainCall`.** The draft's signature does not exist in
   eez-core `84a5b52`. The plugin follows the Solidity (selector `0xc3fb5f3d`), verified by running
   the real contracts. The draft needs to catch up, or the contract needs to change.
2. **Bracket gas.** L1 call hashes fold `gas = 0`, but `EEZL2` hashes the incoming call's gas. With
   `gas ≠ 0` the two disagree. rollupX rejects non-zero gas; the spec is silent.
3. **Settlement wrapper ABI.** The draft names `submitCandidate` but defines no ABI. The plugin
   decodes `<name>(batch)` with the name configurable, or a direct `postAndVerifyBatch`. A wrapper
   called through another contract would need traces.
4. **Genesis parameters.** `FEE_COLLECTOR`, rollup id, manager, wrapper, the `EEZL2` bytecode and
   the genesis reference block are not fixed. `genesis/eez-dev.json` uses placeholders and has no
   `EEZL2` code. Besu's Prague rules require a `depositContractAddress`; both EEZ genesis files set
   the zero address, which is never used and is not part of the genesis hash.
5. **End-to-end fixtures.** No draft-conformant devnet exists, so derivation is tested against
   fakes, not a real settlement chain.
6. **Draft production blockers** (ch. 1.5, 3.3, 7.4) remain.
7. **rollupX derivation.** Following the live network from Chiado, rather than replaying its L2
   blocks, needs rollupX's derivation and the L1-history checks (6.1).
8. **Building rollupX blocks.** Group-level balance clearing runs in block import only. A block
   built with `testing_buildBlockV1` would miss it, so building rollupX blocks needs the same rule in
   the block creator.

## 9. Running it

```sh
# Besu with the core changes, published for the plugin build
cd ~/dev/besu && ./gradlew installDist prepareDetachedTestPlugins
# the plugin
cd ~/dev/besu-eez-plugin && ./gradlew test jar
mkdir -p plugins && cp build/libs/besu-eez-plugin.jar plugins/
BESU_OPTS=-Dbesu.plugins.dir=$PWD/plugins ~/dev/besu/build/install/besu/bin/besu \
  --genesis-file=genesis/eez-dev.json --p2p-enabled=false \
  --Xin-process-rpc-enabled --Xin-process-rpc-apis=ENGINE,TESTING,ETH \
  --plugin-eez-l1-rpc-url=<execution RPC> --plugin-eez-l1-beacon-url=<beacon REST>
```

`--plugin-eez-follower-enabled=false` applies only the execution rules, which is how the draft
smoke test ran: a `0x45` block built with `testing_buildBlockV1` and imported through
`engine_newPayloadV4` was VALID, and its RPC representation, fee routing, base fee and header
fields matched the draft.

To replay the live EEZ-X testnet, start Besu with `--genesis-file=genesis/rollupx-chiado.json
--network-id=699169 --engine-rpc-port=18551 --engine-jwt-disabled`, then feed it the live blocks
(`eth_getBlockByNumber` plus `debug_getRawBlock` from `https://eez.dev/rpc/l2`) through
`engine_newPayloadV4` and `engine_forkchoiceUpdatedV3`.
