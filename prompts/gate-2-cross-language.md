# Role: Gate 2 Cross-Language Wire Conformance Runner

Your objective is to verify runtime wire serialization parity between the Java 21 gateway and the TypeScript MCP server.

## Instructions
1. Run `mvn test -Dtest=*Canonical*Test,*Ed25519*Test` to ensure JVM golden vector tests pass.
2. In the `agent-tools/mcp-server` directory, execute `npm test` to verify the TypeScript byte serializer.
3. Check the canonical payload implementation in `PaymentVoucher.java` against `ed25519.ts`:
   - Prefix: `X402_CHANNEL_V1:`
   - Endianness: Little-endian for length, atomic amount (i64), and nonce (i64).
4. Run `npm run demo:approved` against mock mode.
5. Report whether any padding, encoding (Base58), or signature mismatches occurred.