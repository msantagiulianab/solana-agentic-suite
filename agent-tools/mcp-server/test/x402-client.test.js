import { test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import crypto from "node:crypto";
import { X402Client, X402ClientError } from "../dist/x402-client.js";
import { base58Decode } from "../dist/base58.js";
import { buildCanonicalPayload } from "../dist/ed25519.js";

const CLEAN_ADDRESS = "4Nd1mBQtrMJVYVfKf2PJy9NZGibCcTRxpETqdrBHu19Y";
const FLAGGED_ADDRESS = "Fc1EwQUZyTEagaDvA1utHXCcZNyG1x2PLt2DfNu1cJdH";

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

function startGateway({ secondStatus = 200 } = {}) {
  const state = { requests: [] };
  const server = http.createServer((req, res) => {
    let body = "";
    req.on("data", (chunk) => (body += chunk));
    req.on("end", () => {
      const signature = req.headers["payment-signature"] ?? req.headers["x-payment"];
      state.requests.push({ hasSignature: Boolean(signature), signature, body });

      if (!signature) {
        const b64 = Buffer.from(JSON.stringify(CHALLENGE), "utf8").toString("base64");
        res.writeHead(402, {
          "content-type": "application/json",
          "PAYMENT-REQUIRED": b64,
          "X-PAYMENT-REQUIRED": b64,
        });
        res.end(JSON.stringify(CHALLENGE));
        return;
      }

      if (secondStatus === 403) {
        res.writeHead(403, { "content-type": "application/json" });
        res.end(JSON.stringify({ error: "PAYMENT_REJECTED", message: "replay detected" }));
        return;
      }

      const address = JSON.parse(body).address;
      const blocked = address === FLAGGED_ADDRESS;
      const receipt = {
        channelId: "chan_smoke_test_001",
        settledAmountAtomic: 5000,
        nonce: 1,
        timestamp: Date.now(),
        status: "VERIFIED",
      };
      res.writeHead(200, {
        "content-type": "application/json",
        "PAYMENT-RESPONSE": Buffer.from(JSON.stringify(receipt), "utf8").toString("base64"),
      });
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
      resolve({ server, state, port: server.address().port }),
    );
  });
}

function verifyVoucherHeader(header) {
  const voucher = JSON.parse(Buffer.from(header, "base64").toString("utf8"));
  const pubBytes = base58Decode(voucher.payerPubkey);
  const spki = Buffer.concat([
    Buffer.from("302a300506032b6570032100", "hex"),
    Buffer.from(pubBytes),
  ]);
  const pub = crypto.createPublicKey({ key: spki, format: "der", type: "spki" });
  const payload = buildCanonicalPayload(
    voucher.channelId,
    BigInt(voucher.cumulativeAmountAtomic),
    BigInt(voucher.nonce),
  );
  const signature = Buffer.from(base58Decode(voucher.signature));
  return { voucher, valid: crypto.verify(null, payload, pub, signature) };
}

test("negotiates a 402 challenge, signs a voucher, and returns the verdict", async () => {
  const gw = await startGateway();
  try {
    const client = new X402Client({
      baseUrl: `http://127.0.0.1:${gw.port}`,
      channelId: "chan_smoke_test_001",
    });
    const result = await client.screenAddress(CLEAN_ADDRESS);
    assert.equal(result.verdict, "CLEAR_TO_TRANSACT");

    assert.equal(gw.state.requests.length, 2);
    assert.equal(gw.state.requests[0].hasSignature, false);
    assert.equal(gw.state.requests[1].hasSignature, true);

    const { voucher, valid } = verifyVoucherHeader(gw.state.requests[1].signature);
    assert.equal(valid, true);
    assert.equal(voucher.channelId, "chan_smoke_test_001");
    assert.equal(voucher.cumulativeAmountAtomic, 5000);
    assert.ok(voucher.nonce > 0);
  } finally {
    gw.server.close();
  }
});

test("returns a BLOCKED verdict for a flagged address", async () => {
  const gw = await startGateway();
  try {
    const client = new X402Client({ baseUrl: `http://127.0.0.1:${gw.port}` });
    const result = await client.screenAddress(FLAGGED_ADDRESS);
    assert.equal(result.verdict, "BLOCKED");
    assert.equal(result.riskScore, 100);
    assert.equal(result.flags[0].category, "OFAC_SANCTIONED");
  } finally {
    gw.server.close();
  }
});

test("throws X402ClientError when the gateway rejects the voucher (403)", async () => {
  const gw = await startGateway({ secondStatus: 403 });
  try {
    const client = new X402Client({ baseUrl: `http://127.0.0.1:${gw.port}` });
    await assert.rejects(
      () => client.screenAddress(CLEAN_ADDRESS),
      (err) => err instanceof X402ClientError && err.status === 403,
    );
  } finally {
    gw.server.close();
  }
});
