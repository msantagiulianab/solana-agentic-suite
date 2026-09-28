import { X402Client } from './x402-client.js';

async function runDemo() {
  console.log("🚀 Initiating Autonomous Agent x402 Handshake...");
  const client = new X402Client({ rwaAttestBaseUrl: 'http://localhost:8080' });
  try {
    const result = await client.attestRwa(
      'DoD8TaZaTENh68nkBwZDH4ovRYBwbeTEYATUEDtHT98v', 
      'TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA'
    );
    console.log("\n✅ TRANSACTION SUCCESSFUL!");
    console.log("Telemetry:", JSON.stringify(result.telemetry, null, 2));
    console.log("Attestation:", JSON.stringify(result.attestation, null, 2));
  } catch (error) {
    console.error("❌ Failed:", error);
  }
}
runDemo();
