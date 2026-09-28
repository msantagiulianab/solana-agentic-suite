#!/usr/bin/env bash
# ============================================================================
# smoke-test.sh — Automated end-to-end x402 protocol smoke test
#
# Exercises the full Solana Enterprise Payment Gateway x402 protocol against a
# running Docker container stack:
#   1. HTTP 402 Payment Required challenge (RFC 9110 / x402 wire headers)
#   2. Signed payment-voucher presentation (Ed25519, in-memory verification)
#   3. Append-only PostgreSQL audit-ledger verification (VERIFIED)
#   4. On-chain channel settlement sweep (mock RPC -> deterministic tx sig)
#   5. Final audit-ledger confirmation (SETTLED + non-null tx_signature)
#
# Prerequisites:
#   - Stack running:  docker compose up -d --build
#   - curl (required) and node >= 12 (required: deterministic Ed25519 signing).
#   - jq is optional; node is used as a JSON parser fallback when jq is absent.
#
# IMPORTANT — fresh stack only:
#   The voucher uses the fixed nonce 1 and the ledger is append-only, so the
#   verifier's in-memory nonce watermark plus the persisted record mean a second
#   run against the SAME app process will fail closed (403 / already-settled).
#   Re-run cleanly with:
#       docker compose down -v && docker compose up -d --build
#
# Exit status: 0 on full success, non-zero on any failed step.
# ============================================================================

set -u

# ---- Configuration ---------------------------------------------------------
BASE_URL="${BASE_URL:-http://localhost:8080}"
CHANNEL_ID="${CHANNEL_ID:-chan_smoke_test_001}"
AMOUNT_ATOMIC="${AMOUNT_ATOMIC:-5000}"
NONCE="${NONCE:-1}"
SCREEN_ADDRESS="${SCREEN_ADDRESS:-4Nd1mBQtrMJVYVfKf2PJy9NZGibCcTRxpETqdrBHu19Y}"

DB_CONTAINER="${DB_CONTAINER:-solana-payment-gateway-db}"
DB_NAME="${DB_NAME:-solana_payment_gateway}"
DB_USER="${DB_USER:-postgres}"

COMPLIANCE_URL="${BASE_URL}/api/v1/compliance/screen-address"
SETTLE_URL="${BASE_URL}/api/v1/settlement/channels/${CHANNEL_ID}/sweep"

# ---- ANSI colours ----------------------------------------------------------
C_RESET=$'\033[0m'
C_PASS=$'\033[1;32m'
C_FAIL=$'\033[1;31m'
C_STEP=$'\033[1;36m'
C_DIM=$'\033[2m'

PASS=0
FAIL=0

ok()   { PASS=$((PASS + 1)); printf "${C_PASS}PASS${C_RESET} ${C_STEP}%s${C_RESET}\n" "$1"; }
ko()   { FAIL=$((FAIL + 1)); printf "${C_FAIL}FAIL${C_RESET} ${C_STEP}%s${C_RESET}${2:+ — ${2}}\n" "$1"; }
info() { printf "${C_DIM}%s${C_RESET}\n" "$1"; }

# ---- JSON extraction (jq preferred, node fallback) --------------------------
node_json() {
  local json="$1" path="$2"
  NODE_PATH_VALUE="$path" node -e '
    let s = "";
    process.stdin.setEncoding("utf8");
    process.stdin.on("data", (d) => (s += d));
    process.stdin.on("end", () => {
      try {
        let v = JSON.parse(s);
        const keys = process.env.NODE_PATH_VALUE.replace(/^\./, "").split(".").filter((k) => k !== "");
        for (const k of keys) {
          if (v == null) break;
          v = v[k];
        }
        process.stdout.write(v == null ? "" : String(v));
      } catch (e) {
        process.stdout.write("");
      }
    });
  ' <<<"$json"
}

json_get() {
  local json="$1" path="$2"
  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$json" | jq -r "$path" 2>/dev/null
  else
    node_json "$json" "$path"
  fi
}

# ---- Deterministic Ed25519 voucher signing (zero external deps) -------------
# Emits two lines: the base58 payer public key, then the Base64-encoded
# PAYMENT-SIGNATURE voucher header. The canonical payload mirrors the JVM's
# PaymentVoucher#getCanonicalPayload() byte layout exactly.
generate_voucher_header() {
  local channel="$1" amount="$2" nonce="$3"
  CHANNEL_ID="$channel" AMOUNT="$amount" NONCE="$nonce" node <<'NODE'
const crypto = require('crypto');

const B58 = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';
function base58Encode(buf) {
  const bytes = Buffer.from(buf);
  let zeros = 0;
  while (zeros < bytes.length && bytes[zeros] === 0) zeros++;
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
  let out = '1'.repeat(zeros);
  while (start < size && b58[start] === 0) start++;
  for (let i = start; i < size; i++) out += B58[b58[i]];
  return out;
}

const channelId = process.env.CHANNEL_ID;
const amount = BigInt(process.env.AMOUNT);
const nonce = BigInt(process.env.NONCE);

// Canonical payload: "X402_CHANNEL_V1:" || u16le(channelLen) || channel || i64le(amount) || i64le(nonce)
const domain = Buffer.from('X402_CHANNEL_V1:', 'utf8');
const chanBuf = Buffer.from(channelId, 'utf8');
const payload = Buffer.alloc(domain.length + 2 + chanBuf.length + 8 + 8);
let off = 0;
domain.copy(payload, off); off += domain.length;
payload.writeUInt16LE(chanBuf.length, off); off += 2;
chanBuf.copy(payload, off); off += chanBuf.length;
payload.writeBigInt64LE(amount, off); off += 8;
payload.writeBigInt64LE(nonce, off);

// Deterministic throwaway payer seed (sha256 of a stable material string).
const seed = crypto.createHash('sha256').update('smoke-test-payer-seed-v1').digest();
// RFC 8410 PKCS#8 DER envelope for an Ed25519 private key (seed).
const der = Buffer.concat([Buffer.from('302e020100300506032b657004220420', 'hex'), seed]);
const priv = crypto.createPrivateKey({ key: der, format: 'der', type: 'pkcs8' });
const pub = crypto.createPublicKey(priv);
const payerPubkey = base58Encode(Buffer.from(pub.export({ format: 'jwk' }).x, 'base64url'));

const sig = crypto.sign(null, payload, priv);
const voucher = {
  channelId,
  payerPubkey,
  cumulativeAmountAtomic: Number(amount),
  nonce: Number(nonce),
  signature: base58Encode(sig)
};
const header = Buffer.from(JSON.stringify(voucher), 'utf8').toString('base64');

console.log(payerPubkey);
console.log(header);
NODE
}


# ---- Scratch space ----------------------------------------------------------
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

# ---- Pre-flight -------------------------------------------------------------
printf "${C_STEP}Pre-flight${C_RESET}: ${BASE_URL} | channel=${CHANNEL_ID} | amount=${AMOUNT_ATOMIC} | nonce=${NONCE}\n"
for tool in curl node; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    printf "${C_FAIL}FAIL${C_RESET} missing required tool: %s\n" "$tool"
    exit 1
  fi
done
if command -v jq >/dev/null 2>&1; then info "JSON parser: jq"; else info "JSON parser: node (jq not detected)"; fi
info "NOTE: this is a fresh-stack smoke test (fixed nonce ${NONCE}); re-run requires a stack reset."

# =============================================================================
# STEP 1 — RFC 9110 x402 challenge (expect HTTP 402 + PAYMENT-REQUIRED)
# =============================================================================
info "STEP 1 — challenge request without payment headers"
status1=$(curl -s -o "$TMP_DIR/step1.json" -D "$TMP_DIR/step1.headers" -w '%{http_code}' \
  -X POST "$COMPLIANCE_URL" \
  -H 'Content-Type: application/json' \
  -d "{\"address\":\"$SCREEN_ADDRESS\"}")
body1=$(cat "$TMP_DIR/step1.json")
price1=$(json_get "$body1" '.priceAtomicUnits')

if [ "$status1" = "402" ] \
   && grep -qi '^PAYMENT-REQUIRED:' "$TMP_DIR/step1.headers" \
   && [ "$price1" = "$AMOUNT_ATOMIC" ]; then
  ok "STEP 1 challenge -> HTTP 402, PAYMENT-REQUIRED present, priceAtomicUnits=${price1}"
else
  ko "STEP 1 challenge" "status=${status1} priceAtomicUnits=${price1}"
fi

# =============================================================================
# STEP 2 — Signed voucher presentation (expect HTTP 200 + PAYMENT-RESPONSE)
# =============================================================================
info "STEP 2 — signed voucher presentation"
voucher_output=$(generate_voucher_header "$CHANNEL_ID" "$AMOUNT_ATOMIC" "$NONCE")
payer_pubkey=$(printf '%s\n' "$voucher_output" | sed -n '1p')
payment_signature=$(printf '%s\n' "$voucher_output" | sed -n '2p')
info "        payerPubkey=${payer_pubkey}"

status2=$(curl -s -o "$TMP_DIR/step2.json" -D "$TMP_DIR/step2.headers" -w '%{http_code}' \
  -X POST "$COMPLIANCE_URL" \
  -H 'Content-Type: application/json' \
  -H "PAYMENT-SIGNATURE: ${payment_signature}" \
  -d "{\"address\":\"$SCREEN_ADDRESS\"}")
body2=$(cat "$TMP_DIR/step2.json")
verdict2=$(json_get "$body2" '.verdict')

if [ "$status2" = "200" ] \
   && grep -qi '^PAYMENT-RESPONSE:' "$TMP_DIR/step2.headers" \
   && [ "$verdict2" = "CLEAR_TO_TRANSACT" ]; then
  ok "STEP 2 voucher -> HTTP 200, verdict=${verdict2}, PAYMENT-RESPONSE present"
else
  ko "STEP 2 voucher" "status=${status2} verdict=${verdict2} body=${body2}"
fi

# =============================================================================
# STEP 3 — Audit ledger state (expect VERIFIED record with amount 5000)
# =============================================================================
info "STEP 3 — audit ledger verification (PostgreSQL)"
sql_verified="SELECT COUNT(*) FROM payment_audit_ledger WHERE channel_id='$CHANNEL_ID' AND status='VERIFIED' AND cumulative_amount_atomic=$AMOUNT_ATOMIC;"
count3=$(docker exec "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -t -A -c "$sql_verified" 2>/dev/null | tr -d '[:space:]')

if [ -n "$count3" ] && [ "$count3" -ge 1 ] 2>/dev/null; then
  ok "STEP 3 ledger -> ${count3} VERIFIED record(s) for ${CHANNEL_ID} @ ${AMOUNT_ATOMIC}"
else
  ko "STEP 3 ledger" "expected >=1 VERIFIED record, found '${count3}'"
fi

# =============================================================================
# STEP 4 — Settlement sweep (expect HTTP 200 + txSignature + settled amount)
# =============================================================================
info "STEP 4 — channel settlement sweep"
status4=$(curl -s -o "$TMP_DIR/step4.json" -w '%{http_code}' -X POST "$SETTLE_URL")
body4=$(cat "$TMP_DIR/step4.json")
tx_sig4=$(json_get "$body4" '.txSignature')
settled4=$(json_get "$body4" '.settledAmountAtomic')

if [ "$status4" = "200" ] && [ -n "$tx_sig4" ] && [ "$settled4" = "$AMOUNT_ATOMIC" ]; then
  ok "STEP 4 sweep -> HTTP 200, settledAmountAtomic=${settled4}, txSignature=${tx_sig4:0:16}…"
else
  ko "STEP 4 sweep" "status=${status4} settledAmountAtomic=${settled4} txSignature=${tx_sig4} body=${body4}"
fi

# =============================================================================
# STEP 5 — Final ledger confirmation (expect SETTLED + non-null tx_signature)
# =============================================================================
info "STEP 5 — final ledger confirmation (PostgreSQL)"
sql_settled="SELECT COUNT(*) FROM payment_audit_ledger WHERE channel_id='$CHANNEL_ID' AND status='SETTLED' AND cumulative_amount_atomic=$AMOUNT_ATOMIC AND tx_signature IS NOT NULL AND tx_signature <> '';"
count5=$(docker exec "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -t -A -c "$sql_settled" 2>/dev/null | tr -d '[:space:]')

if [ -n "$count5" ] && [ "$count5" -ge 1 ] 2>/dev/null; then
  ok "STEP 5 ledger -> ${count5} SETTLED record(s) with non-null tx_signature"
else
  ko "STEP 5 ledger" "expected >=1 SETTLED record with tx_signature, found '${count5}'"
fi

# =============================================================================
# Summary
# =============================================================================
printf '\n========================================================\n'
printf 'Smoke test complete: %s passed, %s failed\n' "$PASS" "$FAIL"
printf '========================================================\n'
if [ "$FAIL" -eq 0 ]; then
  printf "${C_PASS}ALL STEPS PASSED${C_RESET}\n"
  exit 0
else
  printf "${C_FAIL}SMOKE TEST FAILED${C_RESET}\n"
  exit 1
fi

