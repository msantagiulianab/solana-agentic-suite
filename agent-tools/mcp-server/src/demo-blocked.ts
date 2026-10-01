import { X402Client } from './x402-client.js';

async function runDemo() {
  console.log("🚀 [demo-blocked] Initiating Autonomous Agent x402 Handshake...");
  const client = new X402Client({ rwaAttestBaseUrl: 'http://localhost:8080' });
  try {
    const result = await client.attestRwa(
      '9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin',
      'TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA'
    );
    const { allowed, reason } = result.attestation;
    console.log("\n🔒 PAYMENT VERIFIED — COMPLIANCE BLOCKED (fail-closed)");
    console.log(`allowed: ${allowed}`);
    console.log(`reason: ${reason}`);
    console.log("Telemetry:", JSON.stringify(result.telemetry, null, 2));
    console.log("Attestation:", JSON.stringify(result.attestation, null, 2));
  } catch (error) {
    console.error("❌ Failed:", error);
  }
}
runDemo();
