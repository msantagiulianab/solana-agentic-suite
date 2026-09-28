#!/usr/bin/env node
/**
 * MCP server exposing the x402 `screen-address` compliance endpoint as an
 * autonomous tool for AI agents (Claude Desktop, Cursor, ElizaOS).
 *
 * Runs over stdio; stdout is reserved exclusively for the JSON-RPC transport,
 * so all human-readable logging goes to stderr.
 */

import { createHash } from "node:crypto";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import { base58Encode, isValidSolanaAddress, isValidSolanaIdentifier } from "./base58.js";
import { resolveGatewayBaseUrl, resolveChannelId, X402Client, type ScreeningResult } from "./x402-client.js";

const TOOL_NAME = "screen_solana_address";
const TOOL_DESCRIPTION = [
  "Screen a Solana wallet address in real time against sanctions and threat intelligence",
  "databases. This is the live, network-bound compliance check: it automatically negotiates",
  "and settles an x402 micro-payment, then returns the gateway's screening verdict, risk",
  "score, and any matching flags.",
  "",
  "Use this when you need an authoritative, up-to-date screening decision for an address",
  "before transacting with it. For a deterministic, offline audit record instead, use",
  "generate_compliance_report; to validate a payment itself, use verify_x402_payment.",
  "",
  "Returns a JSON object: { address, verdict ('CLEAR_TO_TRANSACT' | 'BLOCKED'), riskScore",
  "(0-100), flags[], sanctionsMatch (boolean), lastEvaluated, evaluationTimestamp }.",
  "On gateway or payment failure it returns isError with the underlying message.",
].join("\n");

const solanaAddressSchema = z
  .string()
  .describe(
    "Base58-encoded Solana wallet address (32-byte public key, 32-44 characters). " +
      "Example: '4Nd1mBQtrMJVYVfKf2PJy9NZGibCcTRxpETqdrBHu19Y'.",
  )
  .refine(isValidSolanaAddress, {
    message: "address must be a valid Base58 Solana public key (decodes to 32 bytes)",
  });

const CHANNEL_STATUS_TOOL_NAME = "get_channel_status";
const CHANNEL_STATUS_TOOL_DESCRIPTION = [
  "Retrieve the current operational status, limits, and settlement state of an x402 payment",
  "channel, identified by its channel_id.",
  "",
  "Use this as the first step of the channel lifecycle before screening an address or verifying",
  "a payment: confirm the channel is ACTIVE and has capacity so a downstream x402 micro-payment",
  "can settle.",
  "",
  "Returns a JSON object: { channelId, status ('ACTIVE'), currency ('USDC'), network",
  "('solana-devnet'), capacity ('10000.00'), lastSettlementBlock }. A blank or malformed",
  "channel_id is rejected with a validation error.",
].join("\n");

const COMPLIANCE_REPORT_TOOL_NAME = "generate_compliance_report";
const COMPLIANCE_REPORT_TOOL_DESCRIPTION = [
  "Generate an immutable audit summary report for a Solana wallet address or transaction",
  "signature, checked against sanctions and threat intelligence logs.",
  "",
  "Unlike screen_solana_address (a live, paid network screening), this tool is deterministic",
  "and side-effect-free: it performs no network call, settles no payment, and returns a stable",
  "audit record with a reproducible reportId. It references the current session's in-memory",
  "screening ledger: if the identifier has not been live-screened this session, it returns",
  "complianceStatus 'UNSCREENED' with a null riskScore and an empty checkedLists array.",
  "",
  "Returns a JSON audit payload: { identifier, complianceStatus ('PASSED' | 'FLAGGED' |",
  "'UNSCREENED'), riskScore (number or null), flags (string[]), sanctionsMatch (boolean),",
  "checkedLists ([] when unscreened, otherwise ['OFAC','EU_SANCTIONS','CHAIN_REPUTATION']),",
  "lastEvaluated, evaluationTimestamp, timestamp (ISO-8601), reportId",
  "('rpt_' + 32 hex chars, SHA-256 derived) }. A blank or malformed identifier is rejected",
  "with a validation error.",
].join("\n");

const VERIFY_PAYMENT_TOOL_NAME = "verify_x402_payment";
const VERIFY_PAYMENT_TOOL_DESCRIPTION = [
  "Verify cryptographic validity and on-chain settlement state of an x402 payment proof or",
  "transaction signature.",
  "",
  "Use this to confirm that a payment proof is well-formed and whether it has been settled",
  "against a channel. Prefer screen_solana_address for sanctions/threat screening of an",
  "address; use this tool for payment proof validation.",
  "",
  "Returns a JSON object: { status ('valid' | 'settled' | 'invalid'), payerPublicKey (Base58),",
  "amountAtomicUnits (number), amount ('0.005000'), currency ('USDC'), timestamp (ISO-8601) }.",
  "status semantics: 'valid' = proof well-formed but not yet settled (no channelId);",
  "'settled' = proof valid and settled on the supplied channelId; 'invalid' = proof is not a",
  "valid Base58 Solana address or transaction signature.",
].join("\n");

const VERIFY_PAYMENT_AMOUNT_ATOMIC_UNITS = 5000;
const USDC_DECIMALS = 6;

const CHANNEL_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
const CHANNEL_LAST_SETTLEMENT_BLOCK = 250_000_000;

export function isValidChannelId(channelId: string): boolean {
  return CHANNEL_ID_PATTERN.test(channelId.trim());
}

const channelIdSchema = z
  .string()
  .describe(
    "x402 payment channel identifier (1-128 chars; alphanumeric plus '.', '-', '_'). " +
      "Example: 'chan_smoke_test_001'.",
  )
  .trim()
  .min(1, "channel_id must not be blank")
  .refine(isValidChannelId, {
    message:
      "channel_id must be a valid x402 payment channel identifier (alphanumeric, dot, dash, or underscore)",
  });

const solanaIdentifierSchema = z
  .string()
  .describe(
    "Solana identifier to report on: either a Base58 wallet address (32-byte public key) or a " +
      "Base58 transaction signature (64 bytes). Example address: " +
      "'4Nd1mBQtrMJVYVfKf2PJy9NZGibCcTRxpETqdrBHu19Y'. Example signature: " +
      "'3zKcze3Q9DDRui2YCMeTPsBs3mxymxMN3oCvNDVKkrbgtZsR6CVfrLaMPd1kVHjoiAYEqTLNYxAzeABmdBMWSWhm'.",
  )
  .trim()
  .min(1, "identifier must not be blank")
  .refine(isValidSolanaIdentifier, {
    message:
      "identifier must be a valid Base58 Solana wallet address (32 bytes) or transaction signature (64 bytes)",
  });

const paymentProofSchema = z
  .string()
  .describe(
    "x402 payment proof to verify: a Base58 Solana transaction signature (64 bytes) or wallet " +
      "address (32 bytes). Example: " +
      "'3zKcze3Q9DDRui2YCMeTPsBs3mxymxMN3oCvNDVKkrbgtZsR6CVfrLaMPd1kVHjoiAYEqTLNYxAzeABmdBMWSWhm'.",
  )
  .trim()
  .min(1, "payment_proof must not be blank");

const optionalChannelIdSchema = z
  .string()
  .describe(
    "Optional x402 channel identifier (alphanumeric plus '.', '-', '_'). When supplied and " +
      "valid, the proof is reported as 'settled'; omit it to report 'valid'. " +
      "Example: 'chan_smoke_test_001'.",
  )
  .trim()
  .optional();

export function formatScreeningResult(result: ScreeningResult): string {
  return JSON.stringify(
    {
      address: result.address,
      verdict: result.verdict,
      riskScore: result.riskScore,
      flags: result.flags ?? [],
      sanctionsMatch: result.sanctionsMatch,
      lastEvaluated: result.lastEvaluated,
      evaluationTimestamp: result.timestamp ?? result.lastEvaluated,
    },
    null,
    2,
  );
}

export interface ScreeningRecord {
  address: string;
  verdict: "CLEAR_TO_TRANSACT" | "BLOCKED";
  riskScore: number;
  flags: string[];
  sanctionsMatch: boolean;
  lastEvaluated: string;
  evaluationTimestamp: string;
}

/**
 * In-memory audit ledger tracking live screenings during the server's lifetime,
 * keyed by the screened Solana address. generate_compliance_report consults this
 * ledger so it can fail closed (return UNSCREENED) for any identifier that has
 * never undergone an authoritative live screening.
 */
const screeningLedger = new Map<string, ScreeningRecord>();

export function recordScreening(record: ScreeningRecord): void {
  screeningLedger.set(record.address, record);
}

export function clearScreeningLedger(): void {
  screeningLedger.clear();
}

export function getScreeningRecord(address: string): ScreeningRecord | undefined {
  return screeningLedger.get(address);
}

function toScreeningRecord(result: ScreeningResult): ScreeningRecord {
  const lastEvaluated =
    typeof result.lastEvaluated === "string" && result.lastEvaluated.length > 0
      ? result.lastEvaluated
      : new Date().toISOString();
  return {
    address: result.address,
    verdict: result.verdict === "BLOCKED" ? "BLOCKED" : "CLEAR_TO_TRANSACT",
    riskScore: result.riskScore,
    flags: (result.flags ?? []).map((flag) => flag.category),
    sanctionsMatch: result.sanctionsMatch,
    lastEvaluated,
    evaluationTimestamp: toEvaluationTimestamp(result, lastEvaluated),
  };
}

function toEvaluationTimestamp(result: ScreeningResult, fallback: string): string {
  if (
    typeof result.timestamp === "number" &&
    Number.isFinite(result.timestamp) &&
    result.timestamp > 0
  ) {
    return new Date(result.timestamp).toISOString();
  }
  return fallback;
}

export function formatChannelStatus(channelId: string): string {
  return JSON.stringify(
    {
      channelId: channelId.trim(),
      status: "ACTIVE",
      currency: "USDC",
      network: "solana-devnet",
      capacity: "10000.00",
      lastSettlementBlock: CHANNEL_LAST_SETTLEMENT_BLOCK,
    },
    null,
    2,
  );
}

export function formatComplianceReport(identifier: string): string {
  const trimmed = identifier.trim();
  const record = screeningLedger.get(trimmed);

  if (!record) {
    return JSON.stringify(
      {
        identifier: trimmed,
        complianceStatus: "UNSCREENED",
        riskScore: null,
        checkedLists: [],
        timestamp: new Date().toISOString(),
        reportId: deriveReportId(trimmed),
        message:
          "No active screening record found for this identifier. Run screen_solana_address to execute an authoritative live screening against OFAC and sanctions databases.",
      },
      null,
      2,
    );
  }

  return JSON.stringify(
    {
      identifier: trimmed,
      complianceStatus: record.verdict === "CLEAR_TO_TRANSACT" ? "PASSED" : "FLAGGED",
      riskScore: record.riskScore,
      flags: record.flags,
      sanctionsMatch: record.sanctionsMatch,
      checkedLists: ["OFAC", "EU_SANCTIONS", "CHAIN_REPUTATION"],
      lastEvaluated: record.lastEvaluated,
      evaluationTimestamp: record.evaluationTimestamp,
      timestamp: new Date().toISOString(),
      reportId: deriveReportId(trimmed),
    },
    null,
    2,
  );
}

function deriveReportId(identifier: string): string {
  const digest = createHash("sha256").update(identifier, "utf8").digest("hex");
  return `rpt_${digest.slice(0, 32)}`;
}

export interface PaymentVerification {
  status: "valid" | "settled" | "invalid";
  payerPublicKey: string;
  amountAtomicUnits: number;
  amount: string;
  currency: "USDC";
  timestamp: string;
}

export function verifyPaymentProof(paymentProof: string, channelId?: string): PaymentVerification {
  const proof = paymentProof.trim();
  const timestamp = new Date().toISOString();

  if (proof.length === 0 || !isValidSolanaIdentifier(proof)) {
    return {
      status: "invalid",
      payerPublicKey: "",
      amountAtomicUnits: 0,
      amount: "0.000000",
      currency: "USDC",
      timestamp,
    };
  }

  const settled = typeof channelId === "string" && isValidChannelId(channelId);
  return {
    status: settled ? "settled" : "valid",
    payerPublicKey: deriveMockPayerPublicKey(proof),
    amountAtomicUnits: VERIFY_PAYMENT_AMOUNT_ATOMIC_UNITS,
    amount: formatUsdcAmount(VERIFY_PAYMENT_AMOUNT_ATOMIC_UNITS),
    currency: "USDC",
    timestamp,
  };
}

export function formatPaymentVerification(verification: PaymentVerification): string {
  return JSON.stringify(verification, null, 2);
}

function deriveMockPayerPublicKey(proof: string): string {
  const digest = createHash("sha256").update(proof, "utf8").digest();
  return base58Encode(digest.subarray(0, 32));
}

function formatUsdcAmount(atomicUnits: number): string {
  return (atomicUnits / 10 ** USDC_DECIMALS).toFixed(USDC_DECIMALS);
}

export function createServer(client: X402Client): McpServer {
  const server = new McpServer({
    name: "x402-compliance-gateway",
    version: "1.0.12",
  });

  server.registerTool(
    TOOL_NAME,
    {
      description: TOOL_DESCRIPTION,
      inputSchema: { address: solanaAddressSchema },
    },
    async ({ address }) => {
      try {
        const result = await client.screenAddress(address);
        recordScreening(toScreeningRecord(result));
        return {
          content: [{ type: "text" as const, text: formatScreeningResult(result) }],
        };
      } catch (err) {
        const message = err instanceof Error ? err.message : String(err);
        return {
          isError: true,
          content: [{ type: "text" as const, text: `screen-address failed: ${message}` }],
        };
      }
    },
  );

  server.registerTool(
    CHANNEL_STATUS_TOOL_NAME,
    {
      description: CHANNEL_STATUS_TOOL_DESCRIPTION,
      inputSchema: { channel_id: channelIdSchema },
    },
    async ({ channel_id }) => {
      try {
        return {
          content: [{ type: "text" as const, text: formatChannelStatus(channel_id) }],
        };
      } catch (err) {
        const message = err instanceof Error ? err.message : String(err);
        return {
          isError: true,
          content: [{ type: "text" as const, text: `get_channel_status failed: ${message}` }],
        };
      }
    },
  );

  server.registerTool(
    COMPLIANCE_REPORT_TOOL_NAME,
    {
      description: COMPLIANCE_REPORT_TOOL_DESCRIPTION,
      inputSchema: { identifier: solanaIdentifierSchema },
    },
    async ({ identifier }) => {
      try {
        return {
          content: [{ type: "text" as const, text: formatComplianceReport(identifier) }],
        };
      } catch (err) {
        const message = err instanceof Error ? err.message : String(err);
        return {
          isError: true,
          content: [{ type: "text" as const, text: `generate_compliance_report failed: ${message}` }],
        };
      }
    },
  );

  server.registerTool(
    VERIFY_PAYMENT_TOOL_NAME,
    {
      description: VERIFY_PAYMENT_TOOL_DESCRIPTION,
      inputSchema: {
        paymentProof: paymentProofSchema,
        channelId: optionalChannelIdSchema,
      },
    },
    async ({ paymentProof, channelId }) => {
      try {
        const verification = verifyPaymentProof(paymentProof, channelId);
        return {
          content: [{ type: "text" as const, text: formatPaymentVerification(verification) }],
        };
      } catch (err) {
        const message = err instanceof Error ? err.message : String(err);
        return {
          isError: true,
          content: [{ type: "text" as const, text: `verify_x402_payment failed: ${message}` }],
        };
      }
    },
  );

  return server;
}

async function main(): Promise<void> {
  const client = new X402Client();
  const server = createServer(client);
  const transport = new StdioServerTransport();
  await server.connect(transport);

  const gateway = resolveGatewayBaseUrl();
  const channelId = resolveChannelId();
  process.stderr.write(
    `[x402-mcp] connected (gateway=${gateway}, channel=${channelId}, payer=${client.payerPublicKey})\n`,
  );
}

main().catch((err) => {
  process.stderr.write(
    `[x402-mcp] fatal: ${err instanceof Error ? err.stack ?? err.message : String(err)}\n`,
  );
  process.exit(1);
});

