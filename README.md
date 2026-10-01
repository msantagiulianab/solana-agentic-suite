# Solana Agentic Suite

**JVM-native Solana compliance, Token-2022 attestation, and x402 micro-payment channels**

<p align="left">
  <img alt="Java 21" src="https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white" />
  <img alt="Spring Boot 3.4" src="https://img.shields.io/badge/Spring_Boot-3.4.3-6DB33F?logo=springboot&logoColor=white" />
  <img alt="PostgreSQL 16" src="https://img.shields.io/badge/PostgreSQL-16-4169E1?logo=postgresql&logoColor=white" />
  <img alt="Flyway" src="https://img.shields.io/badge/Flyway-Schema_Migrations-CC0200?logo=flyway&logoColor=white" />
  <img alt="Docker Compose" src="https://img.shields.io/badge/Docker_Compose-2496ED?logo=docker&logoColor=white" />
  <img alt="x402 v2" src="https://img.shields.io/badge/x402-v2-9945FF" />
  <img alt="Ed25519" src="https://img.shields.io/badge/Ed25519-BouncyCastle-000000" />
  <img alt="Tests 53" src="https://img.shields.io/badge/Tests-53_passed-brightgreen" />
</p>

> **Language / runtime note.** The Maven build targets **Java 21** bytecode
> (`<java.version>21</java.version>`), the Spring Boot 3.4 baseline. Container
> images build and run on **Eclipse Temurin JDK 25** (`eclipse-temurin:25-jdk` /
> `25-jre-alpine`). Both are stated explicitly because the host toolchain is JDK
> 25 while the source level remains Java 21.

The **Solana Agentic Suite** is a single Spring Boot 3.4 / Java 21 runtime that
fuses two complementary, zero-Web3-SDK engines behind one RFC-compliant
[x402](https://github.com/x402-foundation/x402) /
[RFC 9110 §15.5.3](https://www.rfc-editor.org/rfc/rfc9110#section-15.5.3)
`402 Payment Required` gateway:

1. **Pure-JVM compliance & attestation engine (the RWA bridge).** Hand-rolled
   Base58, `compact-u16`, canonical account sorting, Ed25519 (BouncyCastle), and
   wire-level Token-2022 instruction builders drive off-chain KYC/AML gating,
   SHA-256 audit hashing, and on-chain mint dispatch — with no Node.js sidecar,
   Python bridge, or generic Web3 Java wrapper.
2. **RFC-compliant x402 micro-payment channels.** A `OncePerRequestFilter`
   validates Ed25519-signed, off-chain payment vouchers **in-memory in under
   10ms** on the request hot path, persists every verification to an append-only
   PostgreSQL audit ledger, and sweeps cumulative channel balances on-chain in
   batched settlement transactions.

Together they meter every `/api/v1/*` endpoint — including
`POST /api/v1/rwa/attest` — behind the same `<10ms` fail-closed payment gate.

## 📺 Architecture & Live Demo Walkthrough

[![Solana Agentic Suite Walkthrough](https://img.youtube.com/vi/1K0cWbcOBrw/maxresdefault.jpg)](https://youtu.be/1K0cWbcOBrw)

> **Watch (2 min):** Technical walkthrough covering pure Java 21 gateway architecture, fail-closed compliance guardrails, and sub-10ms x402 payment voucher verification.

## Model Context Protocol (MCP) Server

Autonomous AI agents can screen Solana addresses and settle compliance
micro-payments through the included [Model Context Protocol](https://modelcontextprotocol.io)
(MCP) server, located at [`/agent-tools/mcp-server`](./agent-tools/mcp-server).
The server is a **zero-dependency** x402 compliance tool: it speaks the RFC 9110
`402 Payment Required` challenge-and-response protocol natively, signs Ed25519
channel vouchers in-memory (Node.js built-in `crypto`, no Web3 SDK), and
negotiates settlement on every call.

- **Implementation:** TypeScript ([`agent-tools/mcp-server/src`](./agent-tools/mcp-server/src))

### Build & Run

```bash
cd agent-tools/mcp-server
npm install
npm run build
npm start
```

### Configuration

The server reads two environment variables:

| Variable | Value | Purpose |
| --- | --- | --- |
| `X402_GATEWAY_URL` | `http://localhost:8080` | Gateway root URL |
| `X402_CHANNEL_ID` | `chan_smoke_test_001` | x402 payment channel id |

#### Claude Desktop

Add an entry to `claude_desktop_config.json`.

**macOS / Linux**

```json
{
  "mcpServers": {
    "solana-x402-compliance": {
      "command": "node",
      "args": ["agent-tools/mcp-server/dist/index.js"],
      "env": {
        "X402_GATEWAY_URL": "http://localhost:8080",
        "X402_CHANNEL_ID": "chan_smoke_test_001"
      }
    }
  }
}
```

**Windows**

```json
{
  "mcpServers": {
    "solana-x402-compliance": {
      "command": "cmd",
      "args": ["/c", "node", "agent-tools/mcp-server/dist/index.js"],
      "env": {
        "X402_GATEWAY_URL": "http://localhost:8080",
        "X402_CHANNEL_ID": "chan_smoke_test_001"
      }
    }
  }
}
```

#### VS Code Cline

Add the server to `cline_mcp_settings.json`:

```json
{
  "mcpServers": {
    "solana-x402-compliance": {
      "command": "node",
      "args": ["agent-tools/mcp-server/dist/index.js"],
      "env": {
        "X402_GATEWAY_URL": "http://localhost:8080",
        "X402_CHANNEL_ID": "chan_smoke_test_001"
      }
    }
  }
}
```

### Verified Dual Compliance Screening Outcomes

The `screen_solana_address` tool returns one of two verified outcomes:

| Counterparty | Risk score | Verdict | Flags |
| --- | --- | --- | --- |
| Clear counterparty | `0` | `CLEAR_TO_TRANSACT` | none |
| Malicious / sanctioned counterparty | `100` | `BLOCKED` | `OFAC_SANCTIONED` / drainer detection (`EXPLOIT_DRAINER`) |

---

## Table of Contents

1. [Executive Architecture & Core Thesis](#1-executive-architecture--core-thesis)
2. [System Components](#2-system-components)
3. [Complete Protocol Sequence Diagram](#3-complete-protocol-sequence-diagram)
4. [Cryptographic & Voucher Wire Specification](#4-cryptographic--voucher-wire-specification)
5. [HTTP Wire Headers & JSON Schemas](#5-http-wire-headers--json-schemas)
6. [Database Schema & State Transitions](#6-database-schema--state-transitions)
7. [Quickstart & Verification](#7-quickstart--verification)
8. [Configuration Parameters](#8-configuration-parameters)
9. [Production Extension & Customization Guide](#9-production-extension--customization-guide)
10. [Project Layout](#10-project-layout)
11. [Testing](#11-testing)
12. [Security & Compliance Posture](#12-security--compliance-posture)

## 1. Executive Architecture & Core Thesis

### Problem

Base-layer blockchain latency and per-transaction fees are fundamentally
incompatible with high-throughput, **micro-metered** HTTP APIs. Use cases such
as:

- **AI inference** billed per token or per call,
- **RWA (real-world asset) valuation feeds** billed per oracle read,
- **Compliance / sanctions screening** billed per address,

…each issue millions of sub-cent requests per day. Settling every call directly
on Solana would impose block-confirmation latency (hundreds of milliseconds to
seconds) and a per-transaction fee that dwarfs the price of a single metered
call. The economics do not close, and the user experience collapses.

### Solution

The gateway decouples the **metering decision** from the **settlement
transaction** using the x402 HTTP challenge-and-response protocol:

1. **Monotonic unidirectional off-chain state channels.** A client presents a
   signed voucher whose `cumulativeAmountAtomic` only ever increases for a
   channel. The gateway trusts the voucher only insofar as (a) it is signed by
   the channel owner, (b) its nonce is strictly monotonic, and (c) its
   cumulative spend does not exceed the verified on-chain escrow deposit.
2. **RFC 9110 `402 Payment Required` filter.** A Spring `OncePerRequestFilter`
   issues a `PAYMENT-REQUIRED` challenge to unauthenticated callers and accepts a
   `PAYMENT-SIGNATURE` voucher on retry, attaching a `PAYMENT-RESPONSE` receipt
   on success.
3. **< 10ms hot path.** Voucher verification is pure in-memory Ed25519 crypto plus
   a short-TTL cache of the escrow balance. **No synchronous Solana RPC call is
   ever made on the HTTP request path.**
4. **Batched on-chain settlement.** An administrative endpoint sweeps a channel's
   highest-nonce verified cumulative amount to the treasury in a single signed
   transaction, recorded atomically in the audit ledger.

The result is deterministic, single-digit-millisecond payment gating with
fail-closed rejection, a complete audit trail, and on-chain finality only where
it matters: at settlement.

### Zero-Dependency Philosophy

The gateway deliberately avoids all generic Web3 SDK baggage:

| Concern | Implementation | Dependency |
| --- | --- | --- |
| Ed25519 signing/verification | `Ed25519Signer` (BouncyCastle) | `bcprov-jdk18on` only |
| Base58 codec | Hand-rolled `Base58` (no alphabet mistakes) | zero |
| `compact-u16` (shortvec) | Hand-rolled `CompactU16` | zero |
| Canonical account sorting | `SolanaWireTransactionBuilder` | zero |
| Transaction serialization & signing | `SolanaWireTransactionBuilder` + `SolanaKeypairService` | zero |
| Solana RPC | JDK `java.net.http.HttpClient` + Jackson JSON-RPC 2.0 | zero |

There is **no** `@solana/web3.js`, **no** `solana4j`/`p4j`, and **no** runtime
invocation of an external process. Wire transactions are serialized byte-by-byte
from first principles, and the JVM-native crypto stack is the only third-party
cryptographic primitive.

## 2. System Components

The gateway follows a strict layered separation:

```
HTTP Request
   │
   ▼
X402PaymentFilter (OncePerRequestFilter)          ── 402 challenge / 403 reject / pass-through
   │
   ├──▶ ChannelVoucherVerifier (service)          ── in-memory nonce watermark + Ed25519 + ceiling check
   │        └──▶ EscrowBalanceProvider            ── SolanaEscrowVerifier (short-TTL cache)
   │
   ├──▶ PaymentAuditService (service)             ── append VERIFIED record
   │        └──▶ PaymentAuditRepository (JPA)     ── append-only PostgreSQL ledger
   │
   └──▶ downstream @RestController               ── compliance screening, (your) metered APIs

Settlement (admin, off hot path):
   POST /api/v1/settlement/channels/{id}/sweep
   │
   ▼
ChannelSettlementService (service)
   ├──▶ SolanaRpcClient                          ── getLatestBlockhash / sendTransaction (JSON-RPC 2.0)
   ├──▶ SolanaWireTransactionBuilder             ── serialize + sign (in-process)
   └──▶ PaymentAuditRepository                   ── VERIFIED → SETTLED (markSettled)
```

| Component | Package | Responsibility |
| --- | --- | --- |
| `X402PaymentFilter` | `filter` | 402 challenge, header decode, 403 fail-closed, receipt attach |
| `ChannelVoucherVerifier` | `service` | in-memory anti-replay + signature + escrow-ceiling checks |
| `SolanaEscrowVerifier` | `service` | on-chain escrow balance with short-TTL cache (`EscrowBalanceProvider`) |
| `PaymentAuditService` | `service` | append-only audit persistence |
| `ChannelSettlementService` | `service` | on-chain sweep + `VERIFIED → SETTLED` transition |
| `PaymentAuditRepository` | `repository` | read/insert only (no update/delete declared) |
| `SolanaRpcClient` | `rpc` | fail-closed JSON-RPC 2.0 (`getAccountInfo`, `getLatestBlockhash`, `sendTransaction`) |
| `SolanaWireTransactionBuilder` | `serialization` | legacy tx wire format, account sorting, SOL/SPL instructions |
| `Ed25519SignatureVerifier` | `serialization` | BouncyCastle Ed25519 verify |
| `SolanaKeypairService` | `serialization` | keypair derivation + in-process signing |
| `Base58`, `CompactU16`, `SolanaAddressValidator` | `serialization` | zero-dependency codecs & validation |
| `ComplianceService` | `rwa.service` | off-chain KYC/AML + asset-compliance gatekeeper (immutable audit log) |
| `RwaAttestationController` | `rwa.controller` | `POST /api/v1/rwa/attest` — x402-protected attestation endpoint |
| `TokenService`, `SolanaMintService` | `rwa.service` | Token-2022 mint issuance, idempotency + fail-closed pre-flight |
| `SolanaRpcAdapter` | `rwa.rpc` | resilient JSON-RPC 2.0 client for the Token-2022 domain |
| `InvestorRepository`, `AssetTokenRepository`, `AuditLogRepository` | `rwa.repository` | KYC/asset/audit persistence |

## 3. Complete Protocol Sequence Diagram

The full x402 challenge-and-response lifecycle across the client, the gateway
(filter / verifier / ledger / settlement), and the Solana RPC node:

```
 Client                Gateway (Spring Boot)                                Solana RPC        PostgreSQL
   │                           │                                              │                 │
   │ 1. POST /api/v1/...       │                                              │                 │
   │    (no payment headers)   │                                              │                 │
   │ ─────────────────────────▶│                                              │                 │
   │                           │ X402PaymentFilter.shouldNotFilter()          │                 │
   │                           │  · /api/v1/*  → protected                    │                 │
   │                           │  · /api/v1/settlement/* → skip (admin)       │                 │
   │                           │                                              │                 │
   │ 2. HTTP 402 + PAYMENT-REQUIRED (Base64 challenge JSON)                  │                 │
   │ ◀─────────────────────────│                                              │                 │
   │                           │                                              │                 │
   │ 3. POST /api/v1/...       │                                              │                 │
   │    + PAYMENT-SIGNATURE    │                                              │                 │
   │    (Base64 voucher JSON)  │                                              │                 │
   │ ─────────────────────────▶│                                              │                 │
   │                           │ 4. Base64-decode → PaymentVoucher record     │                 │
   │                           │ 5. ChannelVoucherVerifier.verifyVoucher()    │                 │
   │                           │    a. Base58 payer key valid?                │                 │
   │                           │    b. nonce > lastSeenNonce[channel]?        │                 │
   │                           │    c. cumulative ≤ escrow ceiling?           │                 │
   │                           │       (short-TTL cache; NO RPC on hot path)  │                 │
   │                           │    d. Ed25519 verify(canonical bytes)        │                 │
   │                           │    ── any failure → 403 Forbidden (fail-     │                 │
   │                           │       closed) and audit log                  │                 │
   │                           │ 6. PaymentAuditService.recordVerifiedVoucher │                 │
   │                           │ ─────────────────────────────────────────────────────────────────▶  append
   │                           │                                              │              VERIFIED row
   │ 7. HTTP 200 + PAYMENT-RESPONSE (Base64 receipt JSON)                    │                 │
   │ ◀─────────────────────────│                                              │                 │
   │                           │                                              │                 │
   │  [later] POST /api/v1/settlement/channels/{id}/sweep                    │                 │
   │ ─────────────────────────▶│                                              │                 │
   │                           │ 8. ChannelSettlementService.settleChannel()  │                 │
   │                           │    a. load latest VERIFIED record            │                 │
   │                           │    b. getLatestBlockhash() ──────────────────▶│                 │
   │                           │ ◀─────────────────────────────── blockhash   │                 │
   │                           │    c. SolanaWireTransactionBuilder            │                 │
   │                           │       .serializeAndSign() (in-process)       │                 │
   │                           │    d. sendTransaction(wireTx) ───────────────▶│                 │
   │                           │ ◀─────────────────────────────── txSignature │                 │
   │                           │ 9. record.markSettled(txSignature)           │                 │
   │                           │ ─────────────────────────────────────────────────────────────────▶  SETTLED row
   │ 10. HTTP 200 { txSignature, settledAmountAtomic, ... }                  │                 │
   │ ◀─────────────────────────│                                              │                 │
```

Key invariant: **steps 4–6 never touch the network.** The only RPC interactions
are the off-path settlement sweep (step 8), keeping the request hot path
deterministic and sub-10ms.

## 4. Cryptographic & Voucher Wire Specification

### Canonical byte layout

A voucher is signed over a deterministic, domain-separated byte string (see
`PaymentVoucher#getCanonicalPayload()`):

```
"X402_CHANNEL_V1:" || u16le(channelId.length) || channelId || i64le(cumulativeAmountAtomic) || i64le(nonce)
```

| Offset | Size (bytes) | Field | Encoding |
| --- | ---: | --- | --- |
| `0` | 16 | domain tag | ASCII `X402_CHANNEL_V1:` |
| `16` | 2 | `channelId.length` | unsigned 16-bit **little-endian** (`putShort`) |
| `18` | `N` | `channelId` | raw UTF-8 bytes |
| `18 + N` | 8 | `cumulativeAmountAtomic` | signed 64-bit **little-endian** (`putLong`) |
| `26 + N` | 8 | `nonce` | signed 64-bit **little-endian** (`putLong`) |

The domain tag provides cross-protocol disambiguation (a signature over these
bytes can never be replayed against another message scheme). Both integer fields
are little-endian to match the JVM `ByteOrder.LITTLE_ENDIAN` buffer and the
JavaScript reference signer in `smoke-test.sh`, which mirrors the layout exactly
(`writeUInt16LE` + `writeBigInt64LE`).

### Ed25519 over Base58 public keys

- The **payer public key** is a Base58-encoded 32-byte Ed25519 public key.
- The **signature** is a Base58-encoded 64-byte Ed25519 signature.
- Verification is `org.bouncycastle.crypto.signers.Ed25519Signer` initialized in
  `verify` mode with `Ed25519PublicKeyParameters(pubkeyBytes, 0)`.
- `SolanaAddressValidator.isValid()` rejects anything that does not decode to
  exactly 32 bytes — a structural guard that runs before the crypto check.

### Monotonic nonce guarantees & anti-replay mechanics

1. **Strict monotonicity (in-memory).** `ChannelVoucherVerifier` maintains a
   `ConcurrentHashMap<String, Long>` of the highest nonce seen per channel. Any
   voucher whose `nonce <= lastSeenNonce[channel]` is rejected **before** any
   cryptographic work, returning `403 Forbidden`.
2. **Constraint-level replay defense (database).** The audit ledger enforces a
   `UNIQUE (channel_id, nonce)` index (`uk_payment_audit_ledger_channel_nonce`),
   so a replayed nonce can never be persisted even if the in-memory watermark is
   bypassed (e.g. after a restart with an empty map).
3. **Monotonic cumulative amount.** Because `cumulativeAmountAtomic` only grows
   across a channel's voucher sequence, the gateway never needs per-call payment
   state — it trusts the latest cumulative value as the running spend ceiling.
4. **Fail-closed ceiling.** If the cumulative amount exceeds the verified escrow
   deposit ceiling (`EscrowBalanceProvider`), the voucher is rejected. A missing,
   depleted, or unreadable escrow account yields a ceiling of `0`, never a
   positive balance.

### Voucher JSON payload

```json
{
  "channelId": "chan_demo_solana_001",
  "payerPubkey": "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU",
  "cumulativeAmountAtomic": 5000,
  "nonce": 10,
  "signature": "<Base58 64-byte Ed25519 signature>"
}
```

## 5. HTTP Wire Headers & JSON Schemas

All x402 headers are **Base64-encoded JSON**. The gateway accepts and emits both
a canonical and an `X-`-prefixed alias for forward compatibility.

| Direction | Canonical header | Alias | Meaning |
| --- | --- | --- | --- |
| Server → Client | `PAYMENT-REQUIRED` | `X-PAYMENT-REQUIRED` | 402 challenge (`PaymentRequired`) |
| Client → Server | `PAYMENT-SIGNATURE` | `X-PAYMENT` | signed voucher (`PaymentVoucher`) |
| Server → Client | `PAYMENT-RESPONSE` | `X-PAYMENT-RESPONSE` | settlement receipt (`PaymentSettlementReceipt`) |

### Challenge (`PAYMENT-REQUIRED`)

```json
{
  "x402Version": 2,
  "scheme": "channel",
  "network": "solana:devnet",
  "escrowAddress": "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU",
  "asset": "USDC",
  "priceAtomicUnits": 5000,
  "unit": "per-call",
  "message": "Payment required via Solana payment channel or gasless voucher"
}
```

### Receipt (`PAYMENT-RESPONSE`)

```json
{
  "channelId": "chan_demo_solana_001",
  "settledAmountAtomic": 5000,
  "nonce": 10,
  "timestamp": 1750000000000,
  "status": "VERIFIED"
}
```

### Example `curl` flows

```bash
# 0) Machine-readable x402 discovery document (unauthenticated)
curl -i http://localhost:8080/.well-known/x402.json
# → HTTP/1.1 200 { "x402Version": 2, "name": "...", "services": [ ... ] }

# 1) Unauthenticated request → 402 challenge
curl -i -X POST http://localhost:8080/api/v1/compliance/screen-address \
  -H 'Content-Type: application/json' \
  -d '{"address":"4Nd1mBQtrMJVYVfKf2PJy9NZGibCcTRxpETqdrBHu19Y"}'
# → HTTP/1.1 402, PAYMENT-REQUIRED: <Base64 challenge JSON>

# 2) Replay with a signed voucher header → 200 + receipt
curl -i -X POST http://localhost:8080/api/v1/compliance/screen-address \
  -H 'Content-Type: application/json' \
  -H "PAYMENT-SIGNATURE: <Base64 voucher JSON>" \
  -d '{"address":"4Nd1mBQtrMJVYVfKf2PJy9NZGibCcTRxpETqdrBHu19Y"}'
# → HTTP/1.1 200, PAYMENT-RESPONSE: <Base64 receipt JSON>

# 2b) x402-protected RWA attestation (unauthenticated → 402, then paid → 200)
curl -i -X POST http://localhost:8080/api/v1/rwa/attest \
  -H 'Content-Type: application/json' \
  -d '{"walletAddress":"4Nd1mBQtrMJVYVfKf2PJy9NZGibCcTRxpETqdrBHu19Y","assetMintAddress":"7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU"}'
# → HTTP/1.1 402, PAYMENT-REQUIRED: <Base64 challenge JSON>
curl -i -X POST http://localhost:8080/api/v1/rwa/attest \
  -H 'Content-Type: application/json' \
  -H "PAYMENT-SIGNATURE: <Base64 voucher JSON>" \
  -d '{"walletAddress":"4Nd1mBQtrMJVYVfKf2PJy9NZGibCcTRxpETqdrBHu19Y","assetMintAddress":"7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU"}'
# → HTTP/1.1 200, PAYMENT-RESPONSE: <Base64 receipt JSON>, {"allowed":true,...}

# 3) Administrative on-chain settlement sweep
curl -i -X POST http://localhost:8080/api/v1/settlement/channels/chan_smoke_test_001/sweep
# → HTTP/1.1 200 { "channelId": "...", "settledAmountAtomic": 5000, "txSignature": "...", ... }
```

## 6. Database Schema & State Transitions

The schema is owned exclusively by versioned **Flyway** migrations
(`src/main/resources/db/migration`). Production runs with
`spring.jpa.hibernate.ddl-auto: validate`, so Hibernate never emits DDL — it only
verifies that the JPA entity maps onto the migrated schema.

### `payment_audit_ledger` (V1 → V3)

| Column | Type | Constraints | Notes |
| --- | --- | --- | --- |
| `id` | `bigserial` | `PK` | monotonically increasing append order |
| `channel_id` | `varchar(64)` | `NOT NULL`, indexed | x402 channel identifier |
| `payer_pubkey` | `varchar(44)` | `NOT NULL`, indexed | Base58 32-byte payer public key |
| `cumulative_amount_atomic` | `bigint` | `NOT NULL` | running spend ceiling in atomic units |
| `nonce` | `bigint` | `NOT NULL`, `UNIQUE` w/ channel | anti-replay monotonic counter |
| `signature` | `varchar(88)` | `NOT NULL` | Base58 Ed25519 voucher signature |
| `status` | `varchar(32)` | `NOT NULL` | `VERIFIED` or `SETTLED` |
| `tx_signature` | `varchar(88)` | `NULL` (V3) | on-chain sweep transaction signature |
| `created_at` | `timestamptz` | `NOT NULL DEFAULT NOW()` | immutable append timestamp |

**Indexes**

| Index | Columns | Purpose |
| --- | --- | --- |
| `pk_payment_audit_ledger` | `id` | primary key |
| `uk_payment_audit_ledger_channel_nonce` | `(channel_id, nonce)` **UNIQUE** | constraint-level replay defense |
| `idx_payment_audit_ledger_payer_pubkey` | `payer_pubkey` | payer lookups |
| `idx_payment_audit_ledger_channel_id` | `channel_id` | channel audit reads |

- **V1 — `V1__init_payment_audit_ledger.sql`** creates the table, the unique
  anti-replay index, and the two lookup indexes.
- **V3 — `V3__add_settlement_tx_signature.sql`** adds the nullable
  `tx_signature` column so previously-appended `VERIFIED` rows remain valid until
  swept.

### RWA domain tables (V2)

Created by `V2__create_rwa_tables.sql`, Integrated from the enterprise RWA
compliance engine with pure-JVM zero-dependency wire serialization. These back the x402-protected
`POST /api/v1/rwa/attest` endpoint.

| Table | Purpose |
| --- | --- |
| `investors` | KYC/AML-gated investor records (unique `wallet_address`, indexed) |
| `asset_tokens` | off-chain RWA registry with `mint_address`, compliance, and settlement status |
| `audit_logs` | immutable, append-only compliance audit trail (approved or blocked) |

### Append-only contract

The table is **append-only by contract**: the application never issues `UPDATE`
or `DELETE` against it, the `PaymentAuditRepository` declares no mutation methods
beyond `save` (insert), and the JPA entity exposes no mutators other than the
single legal state transition below.

### Channel state lifecycle

```
        voucher verified & appended
   ┌──────────────────────────────────▶  VERIFIED
   │                                        │
   │                                        │  ChannelSettlementService.settleChannel()
   │                                        │  → record.markSettled(txSignature)
   │                                        ▼
   └────────────────────────────────────  SETTLED
```

`PaymentAuditRecord.markSettled(txSignature)` enforces the only legal transition:

- `VERIFIED → SETTLED` — allowed only once a non-blank `txSignature` is supplied.
- Any other transition (e.g. `SETTLED → SETTLED`, or `null → SETTLED`) throws
  `IllegalStateException`.

The `ChannelSettlementService` sweeps the **highest-nonce** `VERIFIED` record for
a channel (`findTopByChannelIdOrderByNonceDesc`) — because cumulative amounts are
monotonic, the latest record already represents the full outstanding balance, and
settling it atomically closes the channel.

## 7. Quickstart & Verification

### Prerequisites

- **Docker** + **Docker Compose** (for the full stack)
- **JDK 25** (host toolchain; the project compiles to Java 21 bytecode)
- **Maven Wrapper** (`./mvnw`) — no system Maven required
- For the smoke test only: `curl`, `node` (≥ 12), and optionally `jq`

### 1. Launch the full stack

```bash
docker compose up -d --build
```

This builds the multi-stage `Dockerfile` (Temurin 25 builder → Temurin 25 JRE
runtime, non-root `appuser`) and starts:

| Service | Image | Port | Role |
| --- | --- | --- | --- |
| `solana-agentic-suite-db` | `postgres:16-alpine` | `5433` | append-only audit ledger |
| `solana-payment-gateway-app` | built from `./Dockerfile` | `8080` | Spring Boot gateway |

The application waits for the database healthcheck, then applies the Flyway
migrations (`V1`, `V2`, `V3`) on startup.

### 2. Automated end-to-end smoke test

```bash
./smoke-test.sh
```

The script exercises the complete protocol in **five stages** and exits non-zero
on any failure:

| Stage | Action | Assertion |
| --- | --- | --- |
| **1 — Challenge** | `POST /api/v1/compliance/screen-address` without payment headers | `HTTP 402`, `PAYMENT-REQUIRED` header present, `priceAtomicUnits == 5000` |
| **2 — Voucher** | Replay with a deterministically signed `PAYMENT-SIGNATURE` header | `HTTP 200`, `PAYMENT-RESPONSE` present, verdict `CLEAR_TO_TRANSACT` |
| **3 — Ledger (VERIFIED)** | Query `payment_audit_ledger` in PostgreSQL | ≥ 1 `VERIFIED` row with `cumulative_amount_atomic == 5000` |
| **4 — Sweep** | `POST /api/v1/settlement/channels/{id}/sweep` | `HTTP 200`, non-empty `txSignature`, `settledAmountAtomic == 5000` |
| **5 — Ledger (SETTLED)** | Re-query the ledger | ≥ 1 `SETTLED` row with a non-null, non-blank `tx_signature` |

> **Fresh stack only.** The voucher uses the fixed nonce `1` and the ledger is
> append-only, so a second run against the same app process fails closed
> (`403` / already-settled). Reset cleanly with
> `docker compose down -v && docker compose up -d --build`.

### 3. Local development & testing

```bash
./mvnw clean test
```

Runs the full **53-test** JUnit 5 suite against an in-memory H2 database in
PostgreSQL mode (`src/test/resources/application-test.yml`) with Flyway applying
the same `V1`/`V2`/`V3` migrations. RPC mock mode is enabled so the suite is
deterministic and never dials an external Solana node.

```bash
./mvnw spring-boot:run          # run locally (expects localhost PostgreSQL)
```

### 4. Cockpit UI (Angular 18 Executive Dashboard)

A lightweight Angular 18 single-page application under `cockpit-ui/` that renders
real-time operational telemetry from the Spring Boot gateway:

- **Autonomous x402 telemetry** — verified/settled micro-payment counts and the
  in-memory voucher verification SLA budget.
- **Fail-closed compliance verdicts** — the `APPROVED` vs `BLOCKED` attestation
  block rate computed from the immutable RWA audit log.
- **Live audit trail** — a newest-first table of transaction hashes, payer public
  keys, timestamps, and verdicts, merged from both append-only ledgers
  (`audit_logs` + `payment_audit_ledger`).

The dashboard polls the read-only `GET /api/v1/rwa/audit` endpoint (exempt from
the x402 payment filter) every 5 seconds, so new attestations and payments stream
in without a manual refresh.

#### Running locally

```bash
cd cockpit-ui
npm install
npm start
```

The dev server binds to **http://localhost:4200** and proxies `/api` to the
Spring Boot gateway on **http://localhost:8080** (see `cockpit-ui/proxy.conf.json`),
so no CORS configuration is required.

#### End-to-end walkthrough

Run each tier in its own terminal, then fire the autonomous agent demo:

1. **Isolated database** (PostgreSQL 16 on `localhost:5433`):
   ```bash
   docker compose up -d solana-agentic-suite-db
   ```

2. **Spring Boot gateway** (applies Flyway `V1`/`V2`/`V3`, listens on `8080`):
   ```bash
   ./mvnw spring-boot:run
   ```

3. **Cockpit UI** (Angular dev server on `http://localhost:4200`):
   ```bash
   cd cockpit-ui && npm start
   ```

4. **Fire the autonomous agent demo** and watch the dashboard update in real
   time:
   ```bash
   cd agent-tools/mcp-server
   npm run demo:approved    # Happy path: investor KYC verified → allowed: true
   npm run demo:blocked     # Fail-closed path: unaccredited wallet → allowed: false
   ```

   The demo performs an x402 `402` challenge → Ed25519 voucher →
   `POST /api/v1/rwa/attest` round-trip against the gateway. Open
   **http://localhost:4200** to watch the attestation count increment, the block
   rate update, and a new transaction row stream into the "Recent Transactions"
   table within one poll cycle (5s).

   > **Append-only ledger.** Re-running the demo appends new rows (and advances
   > the channel nonce); reset cleanly with
   > `docker compose down -v && docker compose up -d --build`.

## 8. Configuration Parameters

All values live in `src/main/resources/application.yml`. Spring Boot's relaxed
binding maps uppercase/underscore environment variables onto these keys; explicit
`${ENV:default}` placeholders are listed where they exist.

| Property | Default | Environment override | Purpose |
| --- | --- | --- | --- |
| `server.port` | `8080` | `SERVER_PORT` | HTTP listen port |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5433/agentic_suite_db` | `SPRING_DATASOURCE_URL` | JDBC URL |
| `spring.datasource.username` | `postgres` | `SPRING_DATASOURCE_USERNAME` | DB user |
| `spring.datasource.password` | `postgres_secure_password` | `SPRING_DATASOURCE_PASSWORD` | DB password |
| `spring.datasource.driver-class-name` | `org.postgresql.Driver` | — | JDBC driver |
| `spring.datasource.hikari.maximum-pool-size` | `10` | `DB_POOL_MAX_SIZE` | HikariCP pool ceiling |
| `spring.jpa.hibernate.ddl-auto` | `validate` | — | reject schema drift (Flyway owns DDL) |
| `spring.jpa.open-in-view` | `false` | — | disable OSIV anti-pattern |
| `spring.flyway.enabled` | `true` | — | enable migrations |
| `spring.flyway.locations` | `classpath:db/migration` | — | migration search path |
| `spring.flyway.baseline-on-migrate` | `true` | — | baseline non-empty schemas |
| `spring.flyway.baseline-version` | `1` | — | baseline version |
| `x402.network` | `solana:devnet` | — | challenge `network` field |
| `x402.escrow-pubkey` | `7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU` | — | settlement escrow address |
| `x402.asset` | `USDC` | — | challenge `asset` field |
| `x402.price-atomic-units` | `5000` | — | per-call price (atomic units) |
| `x402.unit` | `per-call` | — | challenge `unit` field |
| `solana.rpc.url` | `https://api.devnet.solana.com` | `SOLANA_RPC_URL` | JSON-RPC 2.0 endpoint |
| `solana.rpc.mock-mode` | `false` | `SOLANA_RPC_MOCK_MODE` | offline deterministic RPC (tests/local) |
| `solana.rpc.cache-ttl-millis` | `5000` | — | escrow balance cache TTL |
| `solana.gateway.treasury-pubkey` | `4Nd1mBQtrMJVYVfKf2PJy9NZGibCcTRxpETqdrBHu19Y` | `SOLANA_GATEWAY_TREASURY_PUBKEY` | settlement sweep destination |
| `solana.gateway.mock-private-key` | `4wBqpZM9xaSheZzJSMawUKKwhdpChKbZ5eu5ky4Vigw` | `SOLANA_GATEWAY_MOCK_PRIVATE_KEY` | deterministic sweep signer (mock only) |

> **Security.** `solana.gateway.mock-private-key` is a deterministic **mock**
> seed used only for offline/local settlement. In production, replace it with a
> secret-manager-backed key (e.g. KMS/HSM or an environment-injected secret) and
> never commit a funded key.

## 9. Production Extension & Customization Guide

This gateway is a template middleware, not a fixed product. The sections below
show how to adapt it to any metered API domain — new endpoints, dynamic pricing,
token settlement, and custom escrow programs.

### (a) Securing New Endpoints

The `X402PaymentFilter` protects **everything under `/api/v1/`** except the
administrative settlement paths. To place a new metered resource behind x402,
simply map a controller under `/api/v1/`:

```java
@RestController
@RequestMapping("/api/v1/ai")
public class InferenceController {
    @PostMapping("/infer")
    public Map<String, Object> infer(@RequestBody InferenceRequest request) {
        return inferenceEngine.run(request);
    }
}

@RestController
@RequestMapping("/api/v1/rwa")
public class OracleController {
    @GetMapping("/oracle/{asset}")
    public Map<String, Object> valuation(@PathVariable String asset) {
        return valuationFeed.price(asset);
    }
}
```

Both `/api/v1/ai/infer` and `/api/v1/rwa/oracle` are now automatically gated: a
request without a `PAYMENT-SIGNATURE` header receives `402`, and a valid voucher
is required to reach the controller.

To tune which paths are protected or excluded, override
`X402PaymentFilter#shouldNotFilter(HttpServletRequest)`:

```java
@Override
protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    if (path.startsWith("/api/v1/settlement/")) return true;   // admin, unpaid
    if (path.startsWith("/api/v1/public/"))    return true;      // free endpoints
    return !path.startsWith("/api/v1/");
}
```

### (b) Custom Pricing & Metering

By default the filter charges a single static price
(`x402.price-atomic-units: 5000`) for every call. For request-shape or user-tier
pricing, replace the `@Value`-injected `unitPriceAtomic` with a pricing service:

```java
@Service
public class RequestPricingService {
    public long priceFor(HttpServletRequest request) {
        return switch (request.getRequestURI()) {
            case "/api/v1/ai/infer"      -> 5000;   // 0.005 USDC per inference
            case "/api/v1/rwa/oracle"    -> 10000;  // 0.010 USDC per oracle read
            default                      -> 5000;   // fallback per-call price
        };
    }
}
```

Then inject `RequestPricingService` into `X402PaymentFilter` and compute the
per-request price at the top of `doFilterInternal`, passing it into both the
`402` challenge and the verifier call:

```java
long price = pricingService.priceFor(request);
boolean authorized = voucherVerifier.verifyVoucher(voucher, price);
```

For **tiered billing** (free tier, standard, enterprise), look up the payer's tier
from `voucher.payerPubkey()` (or an internal customer registry) and multiply the
base price accordingly. The voucher itself remains monotonic and cumulative — the
client simply signs `cumulativeAmountAtomic += price` on each call.

### (c) Token Adaptation (SOL → SPL / Token-2022)

`SolanaWireTransactionBuilder` ships with two instruction factories. Native SOL
settlement uses the System program (`0x02` discriminator):

```java
byte[] source = payer.getPublicKeyBytes();
byte[] treasury = Base58.decode(treasuryPubkey);
SolanaInstruction transfer = SolanaWireTransactionBuilder.systemTransfer(source, treasury, amount);
```

To settle in **SPL Tokens** (e.g. USDC) instead of native SOL, switch the
instruction in `ChannelSettlementService.settleChannel()` to the legacy SPL Token
`transfer` (`0x03` discriminator, program id
`TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA`):

```java
// USDC is a 6-decimal SPL token, so scale the raw amount by 10^6.
long amountRaw = amountAtomic * 1_000_000L;
SolanaInstruction transfer = SolanaWireTransactionBuilder.tokenTransfer(
        sourceTokenAccount,      // gateway's associated token account
        treasuryTokenAccount,     // treasury's associated token account
        authority,                // token authority (signer)
        amountRaw);
```

Notes:

- **Associated Token Accounts (ATAs)** must be resolved (or derived via
  `getAssociatedTokenAddress`) before settling; the builder itself only
  serializes the instruction.
- **Token-2022** uses the program id
  `TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb` and, for mint/transfer-fee tokens,
  the `transferChecked` instruction (which carries the mint + decimals). Add a
  new factory alongside `tokenTransfer` rather than reusing the legacy `0x03`
  discriminator.
- Update `x402.asset` / `x402.price-atomic-units` and the challenge to advertise
  the correct asset and decimal-scaled price.

### (d) Custom Escrow Verification

Escrow balance resolution is behind the `EscrowBalanceProvider` interface, so the
verifier never depends on a concrete address or account layout:

```java
public interface EscrowBalanceProvider {
    long getVerifiedDepositCeiling(String channelId);
}
```

The stock `SolanaEscrowVerifier` resolves a single settlement escrow
(`x402.escrow-pubkey`) and caches the lamport balance for a short TTL. To wire a
**custom smart contract, PDA program, or per-channel escrow**, either:

1. **Override address resolution.** Replace `SolanaEscrowVerifier#resolveEscrowAddress`
   with PDA derivation (e.g. `findProgramAddress(seeds, programId)`) so each
   channel resolves to its own escrow account; or
2. **Provide your own bean.** Implement `EscrowBalanceProvider`, parse the
   account's `data` field for a custom balance layout (not just `lamports`), and
   expose it as the primary `@Component`. The filter/verifier chain is unchanged.

The critical contract — **fail closed** — must be preserved: a missing, depleted,
or unreadable escrow must yield a ceiling of `0`, never a positive balance.

```java
@Component
public class CustomProgramEscrowVerifier implements EscrowBalanceProvider {
    private final SolanaRpcClient rpc;

    public CustomProgramEscrowVerifier(SolanaRpcClient rpc) { this.rpc = rpc; }

    @Override
    public long getVerifiedDepositCeiling(String channelId) {
        String escrow = deriveEscrowPda(channelId);          // program-owned PDA
        return rpc.getAccountInfo(escrow)
                .map(AccountInfoResponse::value)
                .map(AccountInfoValue::data)
                .map(data -> decodeDepositCeiling(data))      // custom layout
                .orElse(0L);                                  // fail closed
    }
}
```

## 10. Project Layout

```
.
├── pom.xml                                  # Java 21 target, Spring Boot 3.4.3, BouncyCastle 1.78.1
├── Dockerfile                               # multi-stage: Temurin 25 builder → 25-jre-alpine runtime
├── docker-compose.yml                       # PostgreSQL 16 + gateway app
├── smoke-test.sh                            # 5-stage end-to-end x402 verification
├── mvnw / mvnw.cmd / .mvn/wrapper/          # Maven wrapper (no system Maven needed)
├── cockpit-ui/                              # Angular 18 executive telemetry dashboard
├── agent-tools/mcp-server/                  # TypeScript zero-dependency x402 MCP server and demo runners
└── src
    ├── main
    │   ├── java/com/msb/solana
    │   │   ├── gateway
    │   │   │   ├── SolanaPaymentGatewayApplication.java
    │   │   │   ├── compliance/        AddressRiskEvaluator, ThreatIntelligenceRegistry, ScreeningVerdict, ScreeningResult, ScreeningFlag, ComplianceExceptionHandler, InvalidSolanaAddressException, RiskCategory, ScreenAddressRequest, ThreatIntelligenceEntry
    │   │   │   ├── config/            SolanaRpcConfig.java (JDK HttpClient bean)
    │   │   │   ├── controller/        ComplianceScreeningController, SettlementController, X402DiscoveryController
    │   │   │   ├── entity/            PaymentAuditRecord, PaymentAuditStatus
    │   │   │   ├── filter/            X402PaymentFilter.java
    │   │   │   ├── model/             PaymentRequiredChallenge, PaymentVoucher,
    │   │   │   │                      PaymentSettlementReceipt, SettlementResult, X402DiscoveryResponse
    │   │   │   ├── repository/        PaymentAuditRepository.java
    │   │   │   ├── rpc/               SolanaRpcClient.java (+ model/* JSON-RPC DTOs)
    │   │   │   ├── serialization/     Base58, CompactU16, AccountMeta, SolanaInstruction,
    │   │   │   │                      SolanaKeypair, SolanaKeypairService,
    │   │   │   │                      SolanaWireTransactionBuilder, Ed25519SignatureVerifier,
    │   │   │   │                      SolanaAddressValidator
    │   │   │   └── service/           ChannelVoucherVerifier, ChannelSettlementService,
    │   │   │                          PaymentAuditService, SolanaEscrowVerifier,
    │   │   │                          EscrowBalanceProvider
    │   │   └── rwa
    │   │       ├── controller/        RwaAttestationController, AuditController
    │   │       ├── entity/            Investor, AssetToken, AuditLog (+ KYC/compliance/status enums)
    │   │       ├── model/             ComplianceCheckRequest, ComplianceCheckResponse, AssetTokenRegistrationRequest, AuditDashboardResponse
    │   │       ├── repository/        InvestorRepository, AssetTokenRepository, AuditLogRepository
    │   │       ├── rpc/               SolanaRpcAdapter (+ dto/* JSON-RPC DTOs)
    │   │       ├── serialization/     Token-2022 wire builders, SolanaKeypairService, Base58Codec, SolanaPdaUtil
    │   │       ├── service/           ComplianceService, TokenService, SolanaMintService
    │   │       └── validation/        SolanaAddressValidator, ValidSolanaAddress
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/      V1__init_payment_audit_ledger.sql,
    │                              V2__create_rwa_tables.sql,
    │                              V3__add_settlement_tx_signature.sql
    └── test
        ├── java/...              14 test classes (53 tests)
        └── resources/application-test.yml   # H2 (PostgreSQL mode) + mock RPC
```

## 11. Testing

The suite runs **53 tests** across 14 classes with JUnit 5, Mockito, and MockMvc:

| Test class | Focus |
| --- | --- |
| `AddressRiskEvaluatorTest` | OFAC / drainer blocklist, `CLEAR_TO_TRANSACT` / `BLOCKED`, malformed address → 400 |
| `X402DiscoveryControllerTest` | `GET /.well-known/x402.json` discovery document |
| `X402ProtocolIntegrationTest` | MockMvc: 402 challenge, valid voucher → 200, replay → 403, tampered signature → 403 |
| `SettlementControllerIntegrationTest` | sweep endpoint → `VERIFIED → SETTLED` + `tx_signature` |
| `ChannelSettlementServiceTest` | sweep transaction build, blockhash, sign, broadcast |
| `ChannelVoucherVerifier` (via integration) | nonce watermark, ceiling, signature checks |
| `SolanaEscrowVerifierTest` | mock-mode ceiling, cache TTL, fail-closed empty balance |
| `PaymentAuditServiceTest` | append-only record creation |
| `PaymentAuditRepositoryTest` | unique `(channel_id, nonce)` constraint, lookups |
| `SolanaRpcClientTest` | JSON-RPC request/response, mock signatures, fail-closed |
| `CryptoPrimitivesTest` | Base58, compact-u16, Ed25519 round-trips |
| `SolanaWireTransactionBuilderTest` | account sorting, header, discriminator, wire bytes |
| `RwaAttestationIntegrationTest` | `POST /api/v1/rwa/attest`: unpaid → 402 challenge; paid → 200 + `PAYMENT-RESPONSE` + compliance verdict |
| `AuditDashboardServiceTest` | metrics aggregation: attestation/payment counts, fail-closed block rate, recent-transaction merge |
| `AuditControllerIntegrationTest` | `GET /api/v1/rwa/audit` — un-gated read endpoint returns metrics + recent transactions |

The MockMvc integration tests verify the five mandated protocol outcomes:

1. Unauthenticated request → `402` with a correct Base64 `PAYMENT-REQUIRED` header.
2. Valid voucher → `200` + downstream payload + `PAYMENT-RESPONSE` header.
3. Tampered signature → `403 Forbidden` (fail-closed).
4. Replayed/stale nonce → `403 Forbidden` with audit logging.
5. Insufficient channel balance → fail-closed rejection.

The project targets **>95% coverage** for the cryptographic (`serialization`)
and filter components.

## 12. Security & Compliance Posture

- **Fail-closed by default.** Missing header → `402`; tampered signature,
  replayed nonce, or exceeded ceiling → `403`. Malformed header → `400`. No code
  path grants access on an unreadable RPC node or escrow.
- **Append-only audit trail.** Every verification is persisted exactly once;
  the schema and repository forbid mutation, and the `UNIQUE(channel_id, nonce)`
  index makes replay impossible at the database layer.
- **No private keys persisted or logged.** `SolanaKeypair` holds the signing key
  in-memory only; the public-key accessor returns a defensive copy. Logs record
  channel IDs, nonces, amounts, and tx signatures — never key material.
- **Non-root container.** The runtime image runs as an unprivileged `appuser`
  with `-XX:+ExitOnOutOfMemoryError` and container-aware heap sizing.
- **Schema drift rejection.** `hibernate.ddl-auto: validate` ensures the JPA
  model can never silently diverge from the versioned Flyway schema.
- **Deterministic mock mode.** `solana.rpc.mock-mode: true` (tests and the local
  compose stack) produces stable, replayable signatures without funded accounts;
  production sets it to `false` to exercise live devnet/mainnet settlement.
