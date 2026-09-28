import { test } from "node:test";
import assert from "node:assert/strict";
import { createPublicKey, verify } from "node:crypto";
import {
  buildCanonicalPayload,
  deriveKeypairFromSeed,
  deriveSeedFromMaterial,
  signCanonicalPayload,
} from "../dist/ed25519.js";
import { base58Encode } from "../dist/base58.js";

// Golden vectors generated with the exact `smoke-test.sh` algorithm
// (seed = sha256("smoke-test-payer-seed-v1"), channel "chan_smoke_test_001",
// amount 5000, nonce 1). These pin the client to the JVM byte layout.
const GOLDEN = {
  seedHex: "d06d37dce237bd89700076a19db86d8680dd584498af9fe7b50cc4be947d1f19",
  payerPubkey: "2uc1Wmo6jxvAo6mX1hTcYyswBeArV8mHc6aNz3wYeVtn",
  payloadHex:
    "583430325f4348414e4e454c5f56313a13006368616e5f736d6f6b655f746573745f30303188130000000000000100000000000000",
  sigBase58:
    "3zKcze3Q9DDRui2YCMeTPsBs3mxymxMN3oCvNDVKkrbgtZsR6CVfrLaMPd1kVHjoiAYEqTLNYxAzeABmdBMWSWhm",
};

test("derives the smoke-test payer public key from the default seed", () => {
  const seed = deriveSeedFromMaterial("smoke-test-payer-seed-v1");
  assert.equal(seed.toString("hex"), GOLDEN.seedHex);

  const kp = deriveKeypairFromSeed(seed);
  assert.equal(base58Encode(kp.publicKey), GOLDEN.payerPubkey);
});

test("canonical payload matches the JVM byte layout exactly", () => {
  const payload = buildCanonicalPayload("chan_smoke_test_001", 5000n, 1n);
  assert.equal(payload.toString("hex"), GOLDEN.payloadHex);
});

test("deterministic Ed25519 signature matches smoke-test.sh", () => {
  const kp = deriveKeypairFromSeed(deriveSeedFromMaterial("smoke-test-payer-seed-v1"));
  const payload = buildCanonicalPayload("chan_smoke_test_001", 5000n, 1n);
  const signature = signCanonicalPayload(payload, kp.privateKey);
  assert.equal(base58Encode(signature), GOLDEN.sigBase58);
});

test("signature verifies against the derived public key", () => {
  const kp = deriveKeypairFromSeed(deriveSeedFromMaterial("smoke-test-payer-seed-v1"));
  const payload = buildCanonicalPayload("chan_smoke_test_001", 5000n, 1n);
  const signature = signCanonicalPayload(payload, kp.privateKey);

  const spki = Buffer.concat([
    Buffer.from("302a300506032b6570032100", "hex"),
    kp.publicKey,
  ]);
  const pub = createPublicKey({ key: spki, format: "der", type: "spki" });
  assert.equal(verify(null, payload, pub, signature), true);
});

test("rejects a seed that is not 32 bytes", () => {
  assert.throws(() => deriveKeypairFromSeed(new Uint8Array(16)), /must be 32 bytes/);
});
