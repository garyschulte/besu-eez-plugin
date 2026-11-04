# Besu design note: a transaction type registry

Status: proposal, 2026-11-03. Covers the registry and the codecs. Section 7 lists the follow-on
pieces (system transaction traits, a pre-execution hook, RPC fields), which build on it.

## 1. Problem

A chain that adds a transaction type has to change Besu core, because the set of types is closed:

- `TransactionType` is an enum in `datatypes`. Nothing outside Besu can add a constant.
- Codecs are wired by hand in static maps: `TransactionDecoder` has two `EnumMap`s (block body and
  pooled forms), `TransactionEncoder` has two `ImmutableMap`s, and legacy transactions bypass the
  maps with `if (FRONTIER)` checks in both classes.
- The decode and encode sides are not symmetric. A decoder receives the opaque bytes with the type
  byte and must skip it itself; an encoder writes only the payload after the type byte.
- Every typed decoder repeats the same bookkeeping: `rawRlp`, `sizeForBlockInclusion`,
  `sizeForAnnouncement`, and `hash(Hash.hash(input))`.

The EEZ work shows the cost. Supporting two EEZ types added about 630 lines of main code to Besu,
most of it EEZ-specific: two enum constants, four codec classes, an EEZ-only `sourceHash` field on
`Transaction`, and EEZ branches in the validator, processor, body validator, pool and RPC.

## 2. Goals and non-goals

Goals:

1. A plugin can define a transaction type, with its codec, during `register()`, without changing
   Besu.
2. Built-in types use the same mechanism, so there is one path for decoding and one for encoding.
3. Decode and encode are symmetric, and the shared bookkeeping happens once.
4. Defining a type does not make it valid anywhere. Validity stays per fork.

Non-goals here: execution semantics of new types (section 7), and changing any built-in wire format.

## 3. Design

### 3.1 `TransactionType` becomes a value class

`TransactionType` changes from an enum to a `final` class with interned instances. The built-in
constants keep their names and methods, so `TransactionType.EIP1559`, `getSerializedType()`,
`getEthSerializedType()`, `supportsAccessList()`, `fromOpaque(byte)` and so on compile unchanged.

```java
public final class TransactionType {
  public static final TransactionType FRONTIER = builtIn(0xf8, 0x00, "FRONTIER", Set.of());
  public static final TransactionType EIP1559 =
      builtIn(0x02, "EIP1559", Set.of(CHAIN_ID, ACCESS_LIST, FEE_MARKET_1559));
  // ACCESS_LIST, BLOB, DELEGATE_CODE likewise

  public enum Feature { CHAIN_ID, ACCESS_LIST, FEE_MARKET_1559, BLOB, CODE_DELEGATION }

  public String name();
  public byte getSerializedType();
  public byte getEthSerializedType();
  public boolean requiresChainId();      // and the other supports*() methods, read from features
  public boolean hasFeature(Feature feature);

  public static Optional<TransactionType> fromOpaque(byte opaqueByte);
  public static Optional<TransactionType> fromEthSerializedType(byte type);
  public static TransactionType valueOf(String name);
  public static List<TransactionType> values();  // built-ins first, then registered, by type byte
  public int index();                            // dense, stable for the process; replaces ordinal()
}
```

The existing boolean traits become a `Feature` set, so a new type states its traits instead of
being special-cased in a static initializer.

Lookups go through a table indexed by type byte, as today. The table is filled by the registry
(3.3), and nothing can be added to it after the registry is sealed.

### 3.2 One codec interface

```java
/** The wire form of one transaction type. */
public interface TransactionCodec {

  /**
   * Reads the payload that follows the type byte (for FRONTIER, the whole RLP list) into a builder.
   * The registry sets the hash, raw RLP and sizes.
   */
  Transaction.Builder decode(RLPInput payload, EncodingContext context);

  /** Writes the payload that follows the type byte. */
  void encode(Transaction transaction, RLPOutput out, EncodingContext context);
}
```

The context is passed to both sides, so the block-body and pooled forms of a type live in one codec.
Blob transactions branch on `context.encodeForBlock()` and `context.elideBlobs()` inside
`BlobTransactionCodec`, instead of having a second map entry. Types without a pooled form ignore
the context.

`TransactionDecoder` and `TransactionEncoder` keep their public static methods and become thin
fronts over the registry:

- decode: read the type byte (or detect a legacy RLP list), look up the codec, call `decode`, then
  set `hash`, `rawRlp`, `sizeForBlockInclusion` and `sizeForAnnouncement` from the opaque bytes. A
  codec can clear the announcement size; blob transactions do, because they are not announced
  without their blobs.
- encode: write the type byte (none for FRONTIER), then call `encode`. Re-encoding from `rawRlp`
  stays as it is today.

The existing `*TransactionDecoder`/`*TransactionEncoder` pairs merge into one `*TransactionCodec`
per type. Their parsing code does not change; the repeated bookkeeping leaves it.

### 3.3 The registry

```java
@Unstable
public interface TransactionTypeRegistry {
  /** Defines a type and its codec. Fails if the byte or name is taken, or after sealing. */
  TransactionType register(TransactionTypeDefinition definition);
}

public record TransactionTypeDefinition(
    int typeByte, String name, Set<TransactionType.Feature> features, TransactionCodec codec) {}
```

- **Where it lives.** The registry is in `ethereum/core`, because codecs depend on `Transaction` and
  RLP. It is the only caller of the package-level hook that adds an instance to `TransactionType`'s
  lookup table in `datatypes`. That hook is public only because `datatypes` cannot see
  `ethereum/core`, and it refuses after sealing.
- **Built-ins.** `MainnetTransactionTypes` registers FRONTIER, ACCESS_LIST, EIP1559, BLOB and
  DELEGATE_CODE through the same `register` call during class initialization, before any plugin
  runs.
- **Plugins.** Plugins reach the registry through a `TransactionTypeService` obtained from the
  `ServiceManager`, in the same way as diega's `ProtocolScheduleService`. Registration is only
  allowed in `register()`.
- **Sealing.** The registry is sealed after plugin registration and before the node is built, the
  same point at which protocol-schedule customizers are resolved. After that the set of types is
  fixed for the life of the process, so nothing decoded earlier can mean something else later.
- **Conflicts.** A duplicate type byte or name, a byte outside `0x00..0x7f`, or a byte EIP-2718
  reserves for legacy transactions (`0xc0..0xfe`) fails startup with the registering plugin named.
- **Scope.** The registry is process-wide, because decoding is static and context-free in Besu
  (block storage, p2p, RPC and the pool all call `TransactionDecoder` directly).

### 3.4 Type-specific fields on `Transaction`

A new type usually carries fields Besu does not model. EEZ `0x45` has `sourceHash`; today that is a
dedicated field on `Transaction`. Instead, `Transaction` gets one opaque slot:

```java
/** Immutable, value-equal data a registered type keeps beside the standard fields. */
public interface TransactionTypeData {}

Transaction.Builder typeData(TransactionTypeData data);
<T extends TransactionTypeData> Optional<T> getTypeData(Class<T> type);
```

`equals`, `hashCode`, `detachedCopy` and the builder's copy constructor carry it. Only the type's
codec reads or writes it. No built-in type uses it.

### 3.5 Validity stays per fork

Registering a type only makes it decodable. A block containing it is still invalid unless the
protocol spec's `TransactionValidatorFactory` accepts the type, exactly as today. A plugin enables
its types on its own chain through the protocol-schedule customizer, and no mainnet fork accepts
them. A node that decodes an unaccepted type in a block rejects the block in validation, not in
decoding. This is the behaviour today for a known type that is not yet active, such as a blob
transaction before Cancun.

## 4. Changes in Besu

| Area | Change |
|---|---|
| `datatypes/TransactionType` | Enum to class (3.1). Built-in constants and methods kept; `ordinal()` replaced by `index()`. |
| `ethereum/core` encoding | `TransactionCodec`, `TransactionTypeRegistry`, `MainnetTransactionTypes`; per-type decoder/encoder pairs merged into codecs; `TransactionDecoder`/`Encoder` front the registry. |
| `Transaction` | `TransactionTypeData` slot (3.4); `switch` in `getPreimage` becomes an `if` chain. |
| `TransactionPool` layer (`AbstractTransactionsLayer`, `AbstractPrioritizedTransactions`) | Arrays sized by `values().length` and indexed by `ordinal()` switch to `index()`; the size is read after sealing. |
| `PendingTransaction.computeMemorySize` | `switch` becomes an `if` chain, with the frontier estimate as the default for registered types. |
| `TransactionBroadcaster`, `TransactionPoolConfiguration`, `TransactionSelectionResults`, `TransactionDecoder` | `EnumSet`/`EnumMap` become `Set`/`Map`. |
| CLI `--tx-pool-max-prioritized-by-type` | picocli's automatic enum conversion is replaced by a converter that calls `TransactionType.valueOf`. |
| GraphQL `TransactionAdapter.getType` | Currently returns `ordinal()`, which happens to equal the type byte for the built-ins. Return the type byte, which is what the schema means. |
| Plugin API | `TransactionTypeService` registered in the `ServiceManager`. |

Tests that `switch` over `TransactionType` (`TransactionTestFixture`, `BlockDataGenerator`,
`LayersTest`, `ReplayTest`, `BaseTransactionPoolTest`) become `if` chains or maps.

## 5. Compatibility and risks

- **Plugin API break.** `TransactionType` is in `datatypes`, which plugins compile against. Code
  that uses the constants and methods keeps compiling. Code that `switch`es on `TransactionType`,
  or builds an `EnumSet`/`EnumMap` of it, does not. That is the price of an open set; the
  alternative is in section 6.
- **Stored data depends on the plugin.** Blocks containing a registered type are only decodable
  with the plugin installed. Starting the node without it fails when it reads such a block. This
  is inherent; the failure should name the unknown type byte.
- **Metrics labels.** The pool labels counts by `type.name()`. Registered types add label values,
  which is the intent.
- **p2p.** A registered type arrives over gossip like any typed transaction. Whether the pool
  accepts it is the pool's and validator's decision, unchanged by this note.
- **Performance.** Lookups stay array-indexed by type byte; the registry adds no per-transaction
  cost after sealing.

## 6. Alternative considered: keep the enum

Add one `EXTENSION` constant and carry the real type byte on each `Transaction` and
`TransactionReceipt`. This avoids the plugin API break. It was rejected because every place that
maps a type to its byte (both codecs, both receipt codecs, announcements, RPC, metrics) would need
an `EXTENSION` special case, and two registered types would share one constant, so per-type pool
limits and metrics could not tell them apart.

## 7. Follow-ons, not covered here

These are needed before EEZ's remaining core changes can leave Besu. Each is generic.

1. **System transaction features.** Additional `Feature` values a type can declare: `UNSIGNED`
   (no signature, sender supplied by the builder), `NO_NONCE`, `NO_FEE` (gas price zero, no
   beneficiary or collector credit, exempt from the base-fee body check), `NOT_POOLED`. These
   replace today's `isProtocolDerived()`. Optimism deposit transactions need the same set.
2. **Pre-execution hook.** A per-type callback, contributed through the protocol spec, that may
   change state in the transaction's updater before the call. EEZ uses it to credit the value a
   `0x45` delivers; an Optimism deposit's mint is the same pattern.
3. **RPC fields.** A per-type contributor of extra JSON fields for `TransactionBaseResult`.

## 8. What EEZ looks like on top

With sections 3 and 7 in place, the plugin owns `0x45` and `0x46`: their definitions, codecs,
`TransactionTypeData` (the `0x45` source hash), validation (a wrapping `TransactionValidatorFactory`
through the existing customizer), the value-credit hook, and RPC fields. Besu keeps no EEZ names,
addresses or constants. The generic pieces it keeps are the registry, the system transaction
features, the pre-execution hook, the base-fee collector, `getCustomSection`, and the
protocol-schedule customizer.

## 9. Open questions

1. **Upstream appetite for the API break (5).** If it is a blocker, section 6 is the fallback.
2. **Sealing point.** Sealing needs a hook between plugin `register()` and node building. The
   customizer resolution added with diega's work is at that point; the two could share it.
3. **Receipt types.** Receipts only carry the type byte, so they need no codec. A future type with
   a non-standard receipt format would need a receipt codec on the definition as well.
