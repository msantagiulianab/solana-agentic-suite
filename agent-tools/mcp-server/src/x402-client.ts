/**
 * Autonomous x402 payment client for the Solana Enterprise Payment Gateway.
 *
 * Zero Web3 dependencies: Ed25519 signing and Base58 encoding use Node.js
 * built-in `crypto` plus the hand-rolled codec in `base58.ts`.
 */

import { base58Encode } from "./base58.js";
import {
  buildCanonicalPayload,
  deriveKeypairFromSeed,
  deriveSeedFromMaterial,
  signCanonicalPayload,
  type Ed25519Keypair,
} from "./ed25519.js";

export interface ScreeningFlag {
  category: string;
  riskScore: number;
  description: string;
}

export interface ScreeningResult {
  address: string;
  verdict: "CLEAR_TO_TRANSACT" | "BLOCKED" | string;
  riskScore: number;
  flags: ScreeningFlag[];
  sanctionsMatch: boolean;
  lastEvaluated: string;
  timestamp: number;
}

export interface X402Challenge {
  x402Version: number;
  scheme: string;
  network: string;
  escrowAddress: string;
  asset: string;
  priceAtomicUnits: number;
  unit: string;
  message?: string;
}

export interface X402ClientOptions {
  /** Gateway root URL. Defaults to X402_GATEWAY_URL (legacy fallback GATEWAY_BASE_URL) or https://msb-solana-enterprise-payment-gateway.duckdns.org. */
  baseUrl?: string;
  /** x402 payment channel id. Defaults to X402_CHANNEL_ID (legacy fallback CHANNEL_ID) or chan_smoke_test_001. */
  channelId?: string;
  /**
   * 32-byte Ed25519 seed, as raw bytes, a 64-char hex string, or a UTF-8
   * material string (hashed with sha256). Defaults to the smoke-test payer
   * seed (sha256("smoke-test-payer-seed-v1")).
   */
  seed?: Uint8Array | string;
  /** Injectable fetch implementation (for tests). Defaults to global fetch. */
  fetchImpl?: typeof fetch;
}

export class X402ClientError extends Error {
  readonly status: number;
  readonly body: string;

  constructor(status: number, body: string) {
    super(`Gateway returned HTTP ${status}: ${body}`);
    this.name = "X402ClientError";
    this.status = status;
    this.body = body;
  }
}

export const DEFAULT_GATEWAY_BASE_URL =
  "https://msb-solana-enterprise-payment-gateway.duckdns.org";

const DEFAULT_CHANNEL_ID = "chan_smoke_test_001";
const DEFAULT_SEED_MATERIAL = "smoke-test-payer-seed-v1";
const DEFAULT_PRICE_ATOMIC_UNITS = 5000;

/**
 * Resolve the gateway root URL from the environment, preferring
 * `X402_GATEWAY_URL` and falling back to the legacy `GATEWAY_BASE_URL`.
 */
export function resolveGatewayBaseUrl(): string {
  return (
    process.env.X402_GATEWAY_URL?.trim() ||
    process.env.GATEWAY_BASE_URL?.trim() ||
    DEFAULT_GATEWAY_BASE_URL
  );
}

/**
 * Resolve the x402 payment channel id, preferring `X402_CHANNEL_ID` and
 * falling back to the legacy `CHANNEL_ID`.
 */
export function resolveChannelId(): string {
  return (
    process.env.X402_CHANNEL_ID?.trim() ||
    process.env.CHANNEL_ID?.trim() ||
    DEFAULT_CHANNEL_ID
  );
}

export class X402Client {
  private readonly baseUrl: string;
  private readonly channelId: string;
  private readonly keypair: Ed25519Keypair;
  private readonly payerPubkey: string;
  private readonly fetchImpl: typeof fetch;

  private lastNonce: bigint = 0n;
  private cumulativeAmountAtomic: bigint = 0n;

  constructor(options: X402ClientOptions = {}) {
    const resolvedBaseUrl = options.baseUrl ?? resolveGatewayBaseUrl();
    this.baseUrl = resolvedBaseUrl.replace(/\/+$/, "");
    this.channelId = options.channelId ?? resolveChannelId();
    this.keypair = deriveKeypairFromSeed(resolveSeed(options.seed));
    this.payerPubkey = base58Encode(this.keypair.publicKey);
    this.fetchImpl = options.fetchImpl ?? globalThis.fetch.bind(globalThis);
  }

  get payerPublicKey(): string {
    return this.payerPubkey;
  }

  get currentCumulativeAmountAtomic(): bigint {
    return this.cumulativeAmountAtomic;
  }

  /**
   * Screens a Solana address, negotiating and settling the x402 micro-payment
   * automatically. Returns the gateway's final screening verdict.
   */
  async screenAddress(address: string): Promise<ScreeningResult> {
    const endpoint = `${this.baseUrl}/api/v1/compliance/screen-address`;
    const body = JSON.stringify({ address });

    let response = await this.fetchImpl(endpoint, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body,
    });

    if (response.status === 402) {
      const challenge = await this.readChallenge(response);
      const voucherHeader = this.signVoucher(challenge.priceAtomicUnits);
      response = await this.fetchImpl(endpoint, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "PAYMENT-SIGNATURE": voucherHeader,
        },
        body,
      });
    }

    if (!response.ok) {
      throw new X402ClientError(response.status, await safeText(response));
    }

    return (await response.json()) as ScreeningResult;
  }

  private signVoucher(priceAtomicUnits?: number): string {
    const nonce = this.nextNonce();
    const price =
      Number.isFinite(priceAtomicUnits) && (priceAtomicUnits as number) > 0
        ? BigInt(Math.trunc(priceAtomicUnits as number))
        : BigInt(DEFAULT_PRICE_ATOMIC_UNITS);
    this.cumulativeAmountAtomic += price;

    const payload = buildCanonicalPayload(this.channelId, this.cumulativeAmountAtomic, nonce);
    const signature = signCanonicalPayload(payload, this.keypair.privateKey);

    const voucher = {
      channelId: this.channelId,
      payerPubkey: this.payerPubkey,
      cumulativeAmountAtomic: toSafeNumber(this.cumulativeAmountAtomic),
      nonce: toSafeNumber(nonce),
      signature: base58Encode(signature),
    };

    return Buffer.from(JSON.stringify(voucher), "utf8").toString("base64");
  }

  private nextNonce(): bigint {
    // Monotonic, wall-clock-seeded nonce: strictly increases across calls and
    // starts far above any nonce previously persisted by the smoke test.
    const now = BigInt(Date.now());
    const candidate = now > this.lastNonce ? now : this.lastNonce + 1n;
    this.lastNonce = candidate;
    return candidate;
  }

  private async readChallenge(response: Response): Promise<X402Challenge> {
    const header =
      response.headers.get("PAYMENT-REQUIRED") ?? response.headers.get("X-PAYMENT-REQUIRED");
    if (header) {
      try {
        const parsed = JSON.parse(
          Buffer.from(header, "base64").toString("utf8"),
        ) as X402Challenge;
        if (parsed && typeof parsed === "object") {
          return parsed;
        }
      } catch {
        // fall through to the body payload
      }
    }

    const text = await response.text();
    try {
      return JSON.parse(text) as X402Challenge;
    } catch {
      return {} as X402Challenge;
    }
  }
}

function resolveSeed(seed?: Uint8Array | string): Uint8Array {
  if (seed !== undefined && seed !== null) {
    if (typeof seed === "string") {
      const trimmed = seed.trim();
      if (/^[0-9a-fA-F]{64}$/.test(trimmed)) {
        return Buffer.from(trimmed, "hex");
      }
      return deriveSeedFromMaterial(trimmed);
    }
    const buf = Buffer.from(seed);
    if (buf.length !== 32) {
      throw new Error(`Ed25519 seed must be 32 bytes, got ${buf.length}`);
    }
    return buf;
  }

  const envSeed = process.env.X402_PRIVATE_KEY_SEED?.trim();
  if (envSeed) {
    if (/^[0-9a-fA-F]{64}$/.test(envSeed)) {
      return Buffer.from(envSeed, "hex");
    }
    return deriveSeedFromMaterial(envSeed);
  }

  return deriveSeedFromMaterial(DEFAULT_SEED_MATERIAL);
}

function toSafeNumber(value: bigint): number {
  if (value > BigInt(Number.MAX_SAFE_INTEGER)) {
    throw new Error(`Value ${value} exceeds Number.MAX_SAFE_INTEGER`);
  }
  return Number(value);
}

async function safeText(response: Response): Promise<string> {
  try {
    return await response.text();
  } catch {
    return "";
  }
}
