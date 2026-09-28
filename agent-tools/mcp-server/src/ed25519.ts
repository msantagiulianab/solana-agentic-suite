/**
 * JVM-native Ed25519 helpers using only Node.js built-in `crypto`.
 *
 * The 32-byte seed is wrapped in the RFC 8410 PKCS#8 DER envelope and the
 * canonical voucher payload is packed exactly as the gateway's
 * `PaymentVoucher#getCanonicalPayload()` (and `smoke-test.sh`):
 *
 *   "X402_CHANNEL_V1:" || u16le(channelId.length) || channelId
 *                     || i64le(cumulativeAmountAtomic) || i64le(nonce)
 */

import {
  createHash,
  createPrivateKey,
  createPublicKey,
  sign,
  type KeyObject,
} from "node:crypto";

export const DOMAIN_TAG = "X402_CHANNEL_V1:";

/** RFC 8410 PKCS#8 DER prefix for an Ed25519 private key (raw 32-byte seed). */
const ED25519_PKCS8_PREFIX = Buffer.from("302e020100300506032b657004220420", "hex");

export interface Ed25519Keypair {
  /** The original 32-byte seed. */
  seed: Buffer;
  /** Derived 32-byte public key. */
  publicKey: Buffer;
  /** In-memory private key handle used for signing. */
  privateKey: KeyObject;
}

export function deriveKeypairFromSeed(seed: Uint8Array): Ed25519Keypair {
  const seedBuf = Buffer.from(seed);
  if (seedBuf.length !== 32) {
    throw new Error(`Ed25519 seed must be 32 bytes, got ${seedBuf.length}`);
  }

  const der = Buffer.concat([ED25519_PKCS8_PREFIX, seedBuf]);
  const privateKey = createPrivateKey({ key: der, format: "der", type: "pkcs8" });
  const publicKey = derivePublicKey(privateKey);
  return { seed: seedBuf, publicKey, privateKey };
}

export function derivePublicKey(privateKey: KeyObject): Buffer {
  const pub = createPublicKey(privateKey);
  const jwk = pub.export({ format: "jwk" }) as { x?: string };
  if (!jwk.x) {
    throw new Error("Failed to derive Ed25519 public key");
  }
  return Buffer.from(jwk.x, "base64url");
}

export function buildCanonicalPayload(
  channelId: string,
  cumulativeAmountAtomic: bigint,
  nonce: bigint,
): Buffer {
  const domain = Buffer.from(DOMAIN_TAG, "utf8");
  const chanBuf = Buffer.from(channelId, "utf8");
  if (chanBuf.length > 0xffff) {
    throw new Error("channelId too long for u16le length prefix (max 65535 bytes)");
  }

  const payload = Buffer.alloc(domain.length + 2 + chanBuf.length + 8 + 8);
  let off = 0;
  domain.copy(payload, off);
  off += domain.length;
  payload.writeUInt16LE(chanBuf.length, off);
  off += 2;
  chanBuf.copy(payload, off);
  off += chanBuf.length;
  payload.writeBigInt64LE(cumulativeAmountAtomic, off);
  off += 8;
  payload.writeBigInt64LE(nonce, off);
  return payload;
}

export function signCanonicalPayload(payload: Buffer, privateKey: KeyObject): Buffer {
  // `null` lets Node infer the Ed25519 algorithm from the key itself.
  return sign(null, payload, privateKey);
}

/**
 * Deterministic 32-byte seed from a UTF-8 material string (sha256), mirroring
 * the throwaway payer seed used by `smoke-test.sh`.
 */
export function deriveSeedFromMaterial(material: string): Buffer {
  return createHash("sha256").update(material, "utf8").digest();
}
