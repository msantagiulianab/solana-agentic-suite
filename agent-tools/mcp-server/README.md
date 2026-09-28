# x402 MCP Server

A lightweight, zero-bloat [Model Context Protocol](https://modelcontextprotocol.io)
(MCP) server that exposes the Solana Enterprise Payment Gateway's
`POST /api/v1/compliance/screen-address` endpoint as an autonomous tool for AI
agents (Claude Desktop, Cursor, VS Code Cline, ElizaOS).

The agent transparently negotiates and settles the x402 micro-payment on every
screen: it receives the HTTP `402 Payment Required` challenge, signs an
Ed25519 channel voucher in-memory (Node.js built-in `crypto`, no Web3 SDK), and
retries with a `PAYMENT-SIGNATURE` header.

## Standalone npm package

The server is published to the npm registry as a standalone package — no clone
or build step is required to run it:

```bash
npx -y @msantagiulianab/x402-mcp-server
```

See [`package.json`](./package.json) for the current version and metadata. The
package ships the compiled ESM `dist/` bundle plus this README and the LICENSE.

## Layout

```
agent-tools/mcp-server/
├── src/
│   ├── base58.ts          # zero-dependency Base58 codec
│   ├── ed25519.ts         # Node crypto Ed25519 keypair + canonical payload
│   ├── x402-client.ts     # autonomous X402Client (402 -> sign -> retry)
│   └── index.ts           # MCP server + tool registration
├── test/                  # offline unit + stdio integration tests
├── test-agent-call.js     # live verification against the running Docker stack
├── package.json
├── smithery.yaml          # Smithery registry manifest
└── tsconfig.json
```

## Install & build

```bash
cd agent-tools/mcp-server
npm install
npm run build          # tsc -> dist/ (ESM)
```

## Run (stdio transport)

```bash
npx -y @msantagiulianab/x402-mcp-server   # published package
# or, from a local checkout:
npm start
# or: node dist/index.js
```

Environment variables:

| Variable | Default | Purpose |
| --- | --- | --- |
| `X402_GATEWAY_URL` | `https://msb-solana-enterprise-payment-gateway.duckdns.org` | Gateway root URL |
| `X402_CHANNEL_ID` | `chan_smoke_test_001` | x402 payment channel id |
| `X402_PRIVATE_KEY_SEED` | sha256 of `smoke-test-payer-seed-v1` | 64-hex or UTF-8 material seed |

> `X402_GATEWAY_URL` and `X402_CHANNEL_ID` are injected by the MCP host
> (Claude Desktop, Cline, Smithery, etc.); `X402_PRIVATE_KEY_SEED` is optional
> and defaults to the deterministic smoke-test payer.

The tool `screen_solana_address`:

- **Description:** _Inspect a Solana address against sanctions and threat
  intelligence databases. Automatically negotiates and settles micro-payments
  via x402._
- **Input:** `address` — a valid Base58 Solana public key (decodes to 32 bytes).
- **Output:** formatted JSON text containing `verdict`, `riskScore`, `flags`,
  and the evaluation timestamp.

The tool `get_channel_status`:

- **Description:** _Retrieve current operational status, limits, and settlement
  state of a given x402 payment channel._
- **Input:** `channel_id` — the unique identifier of the payment channel.
- **Output:** formatted JSON text containing `status` (`"ACTIVE"`), `currency`
  (`"USDC"`), `network` (`"solana-devnet"`), `capacity` (`"10000.00"`), and
  `lastSettlementBlock`.

The tool `generate_compliance_report`:

- **Description:** _Generate an immutable audit summary report for a Solana
  wallet address or transaction signature against sanctions and threat
  intelligence logs._
- **Input:** `identifier` — a Solana wallet address (32-byte Base58) or a
  transaction signature (64-byte Base58).
- **Output:** formatted JSON text containing `complianceStatus` (`"PASSED"` |
  `"FLAGGED"` | `"UNSCREENED"`), `riskScore` (a number for screened
  identifiers, `null` when unscreened), `flags`, `sanctionsMatch`,
  `checkedLists` (`[]` when unscreened, otherwise
  `["OFAC", "EU_SANCTIONS", "CHAIN_REPUTATION"]`), `lastEvaluated`,
  `evaluationTimestamp`, `timestamp`, and `reportId`. The report fails closed:
  it references the session's in-memory screening ledger and returns
  `"UNSCREENED"` until the identifier has been live-screened via
  `screen_solana_address`.

## Architecture & Sync Workflow

This component is one of three independently deployable surfaces in the
repository. Changes flow differently depending on where they live:

| Where the change lives | Sync / deploy action |
| --- | --- |
| **Java backend** (`src/main/...`) | Push to GitHub, then `git pull` on the Hetzner VPS to rebuild/restart the container or jar. |
| **MCP server** (`agent-tools/mcp-server/src/...`) | Bump `version` in `package.json`, then `npm publish`. |
| **Documentation / `smithery.yaml`** (`README.md`, `smithery.yaml`) | Push to GitHub only — no VPS reload and no `npm publish` required. |

> The backend and the MCP server deploy independently: a Java change does not
> require an `npm publish`, and an MCP change does not require a VPS reload.
> Documentation and registry metadata (`smithery.yaml`) ship purely via GitHub.

### `smithery.yaml`

[`smithery.yaml`](./smithery.yaml) is the [Smithery](https://smithery.ai)
registry manifest. It declares the stdio `startCommand`
(`npx -y @msantagiulianab/x402-mcp-server`) and exposes two configurable fields
— `gatewayUrl` and `channelId` — which map onto the `X402_GATEWAY_URL` and
`X402_CHANNEL_ID` environment variables. Updating it only requires a GitHub
push; the Smithery registry indexes it directly from the repository.

## Tests

```bash
npm test
```

This compiles the project and runs:

- `base58.test.js` — Base58 codec round-trips and 32-byte address validation.
- `ed25519.test.js` — golden-vector check that the canonical payload and Ed25519
  signature exactly match `smoke-test.sh` / the JVM `PaymentVoucher` layout.
- `x402-client.test.js` — full 402→sign→retry negotiation against a mock gateway,
  with independent Ed25519 signature verification of the emitted voucher.
- `mcp-server.test.js` — spawns the real server and drives it with an MCP client
  over stdio (list tools + call `screen_solana_address`).

## Live verification against the running stack

With the Docker stack up (`docker compose up -d --build`):

```bash
npm run test:tool
```

This exercises the tool handler against `http://localhost:8080`:

- Clean address `4Nd1mBQtrMJVYVfKf2PJy9NZGibCcTRxpETqdrBHu19Y` → `CLEAR_TO_TRANSACT`.
- Flagged address `Fc1EwQUZyTEagaDvA1utHXCcZNyG1x2PLt2DfNu1cJdH` → `BLOCKED`.

## Client configuration

### Claude Desktop

Add to `claude_desktop_config.json`:

**macOS / Linux**

```json
{
  "mcpServers": {
    "solana-x402-compliance": {
      "command": "npx",
      "args": ["-y", "@msantagiulianab/x402-mcp-server"],
      "env": {
        "X402_GATEWAY_URL": "https://msb-solana-enterprise-payment-gateway.duckdns.org",
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
      "args": ["/c", "npx", "-y", "@msantagiulianab/x402-mcp-server"],
      "env": {
        "X402_GATEWAY_URL": "https://msb-solana-enterprise-payment-gateway.duckdns.org",
        "X402_CHANNEL_ID": "chan_smoke_test_001"
      }
    }
  }
}
```

### VS Code Cline

Add to `cline_mcp_settings.json`:

```json
{
  "mcpServers": {
    "solana-x402-compliance": {
      "command": "npx",
      "args": ["-y", "@msantagiulianab/x402-mcp-server"],
      "env": {
        "X402_GATEWAY_URL": "https://msb-solana-enterprise-payment-gateway.duckdns.org",
        "X402_CHANNEL_ID": "chan_smoke_test_001"
      }
    }
  }
}
```

### Cursor

Add an MCP server entry (Settings → MCP) pointing at the same
`npx -y @msantagiulianab/x402-mcp-server` command with the environment
variables above.

### ElizaOS

Register a stdio-based MCP client plugin using the same
`npx -y @msantagiulianab/x402-mcp-server` command; the tool
`screen_solana_address` becomes callable by the agent.
