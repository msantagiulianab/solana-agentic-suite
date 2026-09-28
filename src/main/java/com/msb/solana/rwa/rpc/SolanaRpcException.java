package com.msb.solana.rwa.rpc;

/**
 * Thrown when a Solana JSON-RPC interaction fails: network timeouts,
 * HTTP error statuses, malformed or null responses, or JSON-RPC error
 * payloads returned by the node.
 *
 * <p>Callers must treat this as a fail-closed signal: never proceed with
 * an on-chain operation when the RPC layer is unavailable.
 */
public class SolanaRpcException extends RuntimeException {

    public SolanaRpcException(String message) {
        super(message);
    }

    public SolanaRpcException(String method, Throwable cause) {
        super("Solana RPC call '" + method + "' failed: " + cause.getMessage(), cause);
    }

    public SolanaRpcException(String method, String detail, Throwable cause) {
        super("Solana RPC call '" + method + "' failed: " + detail + ": " + cause.getMessage(), cause);
    }
}
