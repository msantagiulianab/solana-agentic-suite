import { test } from "node:test";
import assert from "node:assert/strict";
import { base58Decode, base58Encode, isValidSolanaAddress, isValidSolanaIdentifier } from "../dist/base58.js";

test("encodes empty and leading-zero buffers canonically", () => {
  assert.equal(base58Encode(new Uint8Array(0)), "");
  assert.equal(base58Encode(new Uint8Array([0])), "1");
  assert.equal(base58Encode(new Uint8Array([0, 0])), "11");
});

test("round-trips arbitrary byte sequences", () => {
  const samples = [
    new Uint8Array([0, 1, 2, 3, 255, 254, 0]),
    Uint8Array.from({ length: 32 }, (_, i) => (i * 37) % 256),
    new Uint8Array([9]),
  ];
  for (const sample of samples) {
    assert.deepEqual(base58Decode(base58Encode(sample)), sample);
  }
});

test("decodes the smoke-test payer pubkey back to 32 bytes", () => {
  const decoded = base58Decode("2uc1Wmo6jxvAo6mX1hTcYyswBeArV8mHc6aNz3wYeVtn");
  assert.equal(decoded.length, 32);
});

test("rejects invalid Base58 characters", () => {
  assert.throws(() => base58Decode("0OIl"), /Invalid Base58 character/);
});

test("validates 32-byte Solana addresses", () => {
  assert.equal(isValidSolanaAddress("2uc1Wmo6jxvAo6mX1hTcYyswBeArV8mHc6aNz3wYeVtn"), true);
  assert.equal(isValidSolanaAddress("Fc1EwQUZyTEagaDvA1utHXCcZNyG1x2PLt2DfNu1cJdH"), true);
  assert.equal(isValidSolanaAddress("not-an-address"), false);
  assert.equal(isValidSolanaAddress(""), false);
});

test("validates Solana identifiers (32-byte address or 64-byte signature)", () => {
  const signature =
    "3zKcze3Q9DDRui2YCMeTPsBs3mxymxMN3oCvNDVKkrbgtZsR6CVfrLaMPd1kVHjoiAYEqTLNYxAzeABmdBMWSWhm";
  assert.equal(isValidSolanaIdentifier("2uc1Wmo6jxvAo6mX1hTcYyswBeArV8mHc6aNz3wYeVtn"), true);
  assert.equal(isValidSolanaIdentifier("Fc1EwQUZyTEagaDvA1utHXCcZNyG1x2PLt2DfNu1cJdH"), true);
  assert.equal(isValidSolanaIdentifier(signature), true);
  assert.equal(isValidSolanaIdentifier("not-an-address"), false);
  assert.equal(isValidSolanaIdentifier(""), false);
});
