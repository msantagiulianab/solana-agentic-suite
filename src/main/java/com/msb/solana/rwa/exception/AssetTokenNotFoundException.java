package com.msb.solana.rwa.exception;

/**
 * Thrown when an operation requires a registered asset token, but no token
 * record exists for the requested mint address.
 */
public class AssetTokenNotFoundException extends RuntimeException {

    public AssetTokenNotFoundException(String mintAddress) {
        super("Asset token not registered for mint address: " + mintAddress);
    }

    public AssetTokenNotFoundException(String id, boolean byId) {
        super(byId
                ? "Asset token not found for id: " + id
                : "Asset token not registered for mint address: " + id);
    }
}
