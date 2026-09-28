import { test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SERVER_ENTRY = path.resolve(__dirname, "..", "dist", "index.js");

const CLEAN_ADDRESS = "4Nd1mBQtrMJVYVfKf2PJy9NZGibCcTRxpETqdrBHu19Y";
const FLAGGED_ADDRESS = "Fc1EwQUZyTEagaDvA1utHXCcZNyG1x2PLt2DfNu1cJdH";
const TX_SIGNATURE =
  "3zKcze3Q9DDRui2YCMeTPsBs3mxymxMN3oCvNDVKkrbgtZsR6CVfrLaMPd1kVHjoiAYEqTLNYxAzeABmdBMWSWhm";

const CHALLENGE = {
  x402Version: 2,
  scheme: "channel",
  network: "solana:devnet",
  escrowAddress: "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU",
  asset: "USDC",
  priceAtomicUnits: 5000,
  unit: "per-call",
  message: "Payment required via Solana payment channel or gasless voucher",
};

function startGateway() {
  const server = http.createServer((req, res) => {
    let body = "";
    req.on("data", (chunk) => (body += chunk));
    req.on("end", () => {
      const signature = req.headers["payment-signature"];
      if (!signature) {
        const b64 = Buffer.from(JSON.stringify(CHALLENGE), "utf8").toString("base64");
        res.writeHead(402, {
          "content-type": "application/json",
          "PAYMENT-REQUIRED": b64,
        });
        res.end(JSON.stringify(CHALLENGE));
        return;
      }

      const address = JSON.parse(body).address;
      const blocked = address === FLAGGED_ADDRESS;
      res.writeHead(200, { "content-type": "application/json" });
      res.end(
        JSON.stringify({
          address,
          verdict: blocked ? "BLOCKED" : "CLEAR_TO_TRANSACT",
          riskScore: blocked ? 100 : 0,
          flags: blocked
            ? [{ category: "OFAC_SANCTIONED", riskScore: 100, description: "OFAC SDN listed" }]
            : [],
          sanctionsMatch: blocked,
          lastEvaluated: new Date().toISOString(),
          timestamp: Date.now(),
        }),
      );
    });
  });

  return new Promise((resolve) => {
    server.listen(0, "127.0.0.1", () =>
      resolve({ server, port: server.address().port }),
    );
  });
}

async function connectServer() {
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [SERVER_ENTRY],
    env: { ...process.env, GATEWAY_BASE_URL: "http://127.0.0.1:9" },
  });
  const client = new Client({ name: "test-agent", version: "1.0.0" });
  await client.connect(transport);
  return client;
}

test(
  "exposes and executes the screen_solana_address MCP tool over stdio",
  { timeout: 30000 },
  async () => {
    const gw = await startGateway();
    const transport = new StdioClientTransport({
      command: process.execPath,
      args: [SERVER_ENTRY],
      env: { ...process.env, GATEWAY_BASE_URL: `http://127.0.0.1:${gw.port}` },
    });
    const client = new Client({ name: "test-agent", version: "1.0.0" });

    try {
      await client.connect(transport);

      const tools = await client.listTools();
      const tool = tools.tools.find((t) => t.name === "screen_solana_address");
      assert.ok(tool, "screen_solana_address tool is registered");
      assert.match(tool.description, /sanctions and threat intelligence/);

      const cleanResult = await client.callTool({
        name: "screen_solana_address",
        arguments: { address: CLEAN_ADDRESS },
      });
      const cleanText = cleanResult.content.find((c) => c.type === "text")?.text ?? "";
      assert.match(cleanText, /CLEAR_TO_TRANSACT/);

      const blockedResult = await client.callTool({
        name: "screen_solana_address",
        arguments: { address: FLAGGED_ADDRESS },
      });
      const blockedText = blockedResult.content.find((c) => c.type === "text")?.text ?? "";
      assert.match(blockedText, /BLOCKED/);
      assert.match(blockedText, /OFAC_SANCTIONED/);
    } finally {
      await client.close();
      gw.server.close();
    }
  },
);

test(
  "exposes and executes the get_channel_status MCP tool over stdio",
  { timeout: 30000 },
  async () => {
    const client = await connectServer();
    try {
      const tools = await client.listTools();
      const tool = tools.tools.find((t) => t.name === "get_channel_status");
      assert.ok(tool, "get_channel_status tool is registered");
      assert.match(tool.description, /operational status/);

      const result = await client.callTool({
        name: "get_channel_status",
        arguments: { channel_id: "chan_smoke_test_001" },
      });
      const text = result.content.find((c) => c.type === "text")?.text ?? "";
      const payload = JSON.parse(text);
      assert.equal(payload.channelId, "chan_smoke_test_001");
      assert.equal(payload.status, "ACTIVE");
      assert.equal(payload.currency, "USDC");
      assert.equal(payload.network, "solana-devnet");
      assert.equal(payload.capacity, "10000.00");
      assert.ok(Number.isInteger(payload.lastSettlementBlock));
      assert.ok(payload.lastSettlementBlock > 0);

      const blank = await client.callTool({
        name: "get_channel_status",
        arguments: { channel_id: "   " },
      });
      assert.equal(blank.isError, true);

      const invalid = await client.callTool({
        name: "get_channel_status",
        arguments: { channel_id: "not a channel!!" },
      });
      assert.equal(invalid.isError, true);
    } finally {
      await client.close();
    }
  },
);

test(
  "exposes and executes the generate_compliance_report MCP tool over stdio",
  { timeout: 30000 },
  async () => {
    const gw = await startGateway();
    const transport = new StdioClientTransport({
      command: process.execPath,
      args: [SERVER_ENTRY],
      env: { ...process.env, GATEWAY_BASE_URL: `http://127.0.0.1:${gw.port}` },
    });
    const client = new Client({ name: "test-agent", version: "1.0.0" });

    try {
      await client.connect(transport);

      const tools = await client.listTools();
      const tool = tools.tools.find((t) => t.name === "generate_compliance_report");
      assert.ok(tool, "generate_compliance_report tool is registered");
      assert.match(tool.description, /immutable audit summary/);

      // Fail-closed default: an address with no live screening returns UNSCREENED.
      const unscreened = await client.callTool({
        name: "generate_compliance_report",
        arguments: { identifier: CLEAN_ADDRESS },
      });
      const unscreenedText = unscreened.content.find((c) => c.type === "text")?.text ?? "";
      const unscreenedReport = JSON.parse(unscreenedText);
      assert.equal(unscreenedReport.identifier, CLEAN_ADDRESS);
      assert.equal(unscreenedReport.complianceStatus, "UNSCREENED");
      assert.equal(unscreenedReport.riskScore, null);
      assert.deepEqual(unscreenedReport.checkedLists, []);
      assert.ok(unscreenedReport.timestamp, "timestamp is present");
      assert.match(unscreenedReport.reportId, /^rpt_[0-9a-f]{32}$/);
      assert.match(unscreenedReport.message, /screen_solana_address/);

      // A transaction signature can never be live-screened, so it stays UNSCREENED.
      const sigResult = await client.callTool({
        name: "generate_compliance_report",
        arguments: { identifier: TX_SIGNATURE },
      });
      const sigText = sigResult.content.find((c) => c.type === "text")?.text ?? "";
      const sigReport = JSON.parse(sigText);
      assert.equal(sigReport.complianceStatus, "UNSCREENED");
      assert.equal(sigReport.riskScore, null);

      // After a live screening of a clean address, the report reflects the recorded verdict.
      const cleanScreen = await client.callTool({
        name: "screen_solana_address",
        arguments: { address: CLEAN_ADDRESS },
      });
      const cleanScreenText = cleanScreen.content.find((c) => c.type === "text")?.text ?? "";
      assert.match(cleanScreenText, /CLEAR_TO_TRANSACT/);

      const cleanReport = await client.callTool({
        name: "generate_compliance_report",
        arguments: { identifier: CLEAN_ADDRESS },
      });
      const cleanText = cleanReport.content.find((c) => c.type === "text")?.text ?? "";
      const cleanPayload = JSON.parse(cleanText);
      assert.equal(cleanPayload.identifier, CLEAN_ADDRESS);
      assert.equal(cleanPayload.complianceStatus, "PASSED");
      assert.equal(cleanPayload.riskScore, 0);
      assert.deepEqual(cleanPayload.flags, []);
      assert.equal(cleanPayload.sanctionsMatch, false);
      assert.deepEqual(cleanPayload.checkedLists, ["OFAC", "EU_SANCTIONS", "CHAIN_REPUTATION"]);
      assert.ok(cleanPayload.lastEvaluated, "lastEvaluated is present");
      assert.ok(cleanPayload.evaluationTimestamp, "evaluationTimestamp is present");
      assert.match(cleanPayload.reportId, /^rpt_[0-9a-f]{32}$/);

      // After a live screening of a flagged address, the report reflects FLAGGED.
      const flaggedScreen = await client.callTool({
        name: "screen_solana_address",
        arguments: { address: FLAGGED_ADDRESS },
      });
      const flaggedScreenText = flaggedScreen.content.find((c) => c.type === "text")?.text ?? "";
      assert.match(flaggedScreenText, /BLOCKED/);

      const flaggedReport = await client.callTool({
        name: "generate_compliance_report",
        arguments: { identifier: FLAGGED_ADDRESS },
      });
      const flaggedText = flaggedReport.content.find((c) => c.type === "text")?.text ?? "";
      const flaggedPayload = JSON.parse(flaggedText);
      assert.equal(flaggedPayload.identifier, FLAGGED_ADDRESS);
      assert.equal(flaggedPayload.complianceStatus, "FLAGGED");
      assert.equal(flaggedPayload.riskScore, 100);
      assert.equal(flaggedPayload.sanctionsMatch, true);
      assert.deepEqual(flaggedPayload.flags, ["OFAC_SANCTIONED"]);
      assert.deepEqual(flaggedPayload.checkedLists, ["OFAC", "EU_SANCTIONS", "CHAIN_REPUTATION"]);
      assert.match(flaggedText, /OFAC_SANCTIONED/);

      const blank = await client.callTool({
        name: "generate_compliance_report",
        arguments: { identifier: "" },
      });
      assert.equal(blank.isError, true);

      const malformed = await client.callTool({
        name: "generate_compliance_report",
        arguments: { identifier: "not-a-valid-solana-identifier" },
      });
      assert.equal(malformed.isError, true);
    } finally {
      await client.close();
      gw.server.close();
    }
  },
);

test(
  "exposes and executes the verify_x402_payment MCP tool over stdio",
  { timeout: 30000 },
  async () => {
    const client = await connectServer();
    try {
      const tools = await client.listTools();
      const tool = tools.tools.find((t) => t.name === "verify_x402_payment");
      assert.ok(tool, "verify_x402_payment tool is registered");
      assert.match(tool.description, /cryptographic validity/);

      const valid = await client.callTool({
        name: "verify_x402_payment",
        arguments: { paymentProof: TX_SIGNATURE },
      });
      const validText = valid.content.find((c) => c.type === "text")?.text ?? "";
      const validProof = JSON.parse(validText);
      assert.equal(validProof.status, "valid");
      assert.ok(validProof.payerPublicKey, "payer public key is present");
      assert.ok(Number.isInteger(validProof.amountAtomicUnits));
      assert.ok(validProof.amountAtomicUnits > 0);
      assert.equal(validProof.amount, "0.005000");
      assert.equal(validProof.currency, "USDC");
      assert.ok(validProof.timestamp, "timestamp is present");

      const settled = await client.callTool({
        name: "verify_x402_payment",
        arguments: { paymentProof: CLEAN_ADDRESS, channelId: "chan_smoke_test_001" },
      });
      const settledText = settled.content.find((c) => c.type === "text")?.text ?? "";
      const settledProof = JSON.parse(settledText);
      assert.equal(settledProof.status, "settled");

      const invalid = await client.callTool({
        name: "verify_x402_payment",
        arguments: { paymentProof: "not-a-valid-proof" },
      });
      const invalidText = invalid.content.find((c) => c.type === "text")?.text ?? "";
      const invalidProof = JSON.parse(invalidText);
      assert.equal(invalidProof.status, "invalid");
      assert.equal(invalidProof.payerPublicKey, "");
      assert.equal(invalidProof.amountAtomicUnits, 0);

      const blank = await client.callTool({
        name: "verify_x402_payment",
        arguments: { paymentProof: "   " },
      });
      assert.equal(blank.isError, true);
    } finally {
      await client.close();
    }
  },
);
