/**
 * Zero-dependency Base58 codec (Bitcoin/Solana alphabet).
 *
 * Mirrors the JVM `Base58` implementation and the `smoke-test.sh` reference
 * encoding exactly so that public keys and voucher signatures round-trip
 * between this agent client and the gateway verifier.
 */

const ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

const ALPHABET_MAP: Readonly<Record<string, number>> = (() => {
  const map: Record<string, number> = {};
  for (let i = 0; i < ALPHABET.length; i++) {
    map[ALPHABET[i]] = i;
  }
  return map;
})();

export function base58Encode(input: Uint8Array): string {
  const bytes = new Uint8Array(input);
  if (bytes.length === 0) {
    return "";
  }

  let zeros = 0;
  while (zeros < bytes.length && bytes[zeros] === 0) {
    zeros++;
  }

  const size = Math.floor(((bytes.length - zeros) * 138) / 100) + 1;
  const b58 = new Uint8Array(size);
  let length = 0;

  for (let i = zeros; i < bytes.length; i++) {
    let carry = bytes[i];
    let j = 0;
    for (let k = size - 1; (carry !== 0 || j < length) && k >= 0; k--, j++) {
      carry += 256 * b58[k];
      b58[k] = carry % 58;
      carry = Math.floor(carry / 58);
    }
    length = j;
  }

  let start = size - length;
  let out = "1".repeat(zeros);
  while (start < size && b58[start] === 0) {
    start++;
  }
  for (let i = start; i < size; i++) {
    out += ALPHABET[b58[i]];
  }
  return out;
}

export function base58Decode(input: string): Uint8Array {
  if (input.length === 0) {
    return new Uint8Array(0);
  }

  const digits = new Uint8Array(input.length);
  for (let i = 0; i < input.length; i++) {
    const digit = ALPHABET_MAP[input[i]];
    if (digit === undefined) {
      throw new Error(`Invalid Base58 character: ${input[i]}`);
    }
    digits[i] = digit;
  }

  let zeros = 0;
  while (zeros < digits.length && digits[zeros] === 0) {
    zeros++;
  }

  const decoded = new Uint8Array(input.length);
  let outputStart = decoded.length;
  for (let inputStart = zeros; inputStart < digits.length; ) {
    decoded[--outputStart] = divmod58(digits, inputStart, 256);
    if (digits[inputStart] === 0) {
      inputStart++;
    }
  }

  while (outputStart < decoded.length && decoded[outputStart] === 0) {
    outputStart++;
  }

  return decoded.slice(outputStart - zeros, decoded.length);
}

function divmod58(number: Uint8Array, firstDigit: number, divisor: number): number {
  let remainder = 0;
  for (let i = firstDigit; i < number.length; i++) {
    const digit = number[i];
    const temp = remainder * 58 + digit;
    number[i] = Math.floor(temp / divisor);
    remainder = temp % divisor;
  }
  return remainder;
}

/**
 * A Solana public key is 32 raw bytes encoded as Base58 (32-44 chars).
 */
export function isValidSolanaAddress(address: string): boolean {
  try {
    return base58Decode(address.trim()).length === 32;
  } catch {
    return false;
  }
}

/**
 * A Solana identifier is either a 32-byte public key (wallet address) or a
 * 64-byte transaction signature, both Base58-encoded.
 */
export function isValidSolanaIdentifier(identifier: string): boolean {
  try {
    const length = base58Decode(identifier.trim()).length;
    return length === 32 || length === 64;
  } catch {
    return false;
  }
}
