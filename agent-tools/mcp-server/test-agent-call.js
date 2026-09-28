#!/usr/bin/env node
/**
 * Quick live verification of the autonomous x402 MCP tool handler against the
 * running Docker stack (http://localhost:8080).
 *
 * Usage:
 *   npm run build
 *   node test-agent-call.js
 *
 * Environment overrides:
 *   GATEWAY_BASE_URL   (default http://localhost:8080)
 *   X402_CHANNEL_ID    (default chan_smoke_test_001)
 *   X402_PRIVATE_KEY_SEED  (64-hex or UTF-8 material; default smoke-test payer)
 */

import { X402Client } from "./dist/x402-client.js";

const CLEAN_ADDRESS = "4Nd1mBQtrMJVYVfKf2PJy9NZGibCcTRxpETqdrBHu19Y";
const FLAGGED_ADDRESS = "Fc1EwQUZyTEagaDvA1utHXCcZNyG1x2PLt2DfNu1cJdH";

const baseUrl = process.env.GATEWAY_BASE_URL || "http://localhost:8080";
const client = new X402Client({ baseUrl });

let pass = 0;
let fail = 0;

function ok(message) {
  pass += 1;
  console.log(`PASS  ${message}`);
}

function ko(message, extra) {
  fail += 1;
  console.log(`FAIL  ${message}${extra ? ` — ${extra}` : ""}`);
}

async function check(address, expectedVerdict) {
  try {
    const result = await client.screenAddress(address);
    if (result.verdict === expectedVerdict) {
      ok(`${address.slice(0, 10)}… → ${result.verdict} (riskScore=${result.riskScore})`);
    } else {
      ko(`${address} → expected ${expectedVerdict}, got ${result.verdict}`, JSON.stringify(result));
    }
  } catch (err) {
    ko(`${address} → exception`, err instanceof Error ? err.message : String(err));
  }
}

console.log(`[test-agent-call] gateway=${baseUrl} payer=${client.payerPublicKey}\n`);

await check(CLEAN_ADDRESS, "CLEAR_TO_TRANSACT");
await check(FLAGGED_ADDRESS, "BLOCKED");

console.log(`\n========================================================`);
console.log(`tool verification: ${pass} passed, ${fail} failed`);
console.log(`========================================================`);
if (fail === 0) {
  console.log("ALL CHECKS PASSED");
  process.exitCode = 0;
} else {
  console.log("VERIFICATION FAILED (is the Docker stack up? try: docker compose up -d --build)");
  process.exitCode = 1;
}