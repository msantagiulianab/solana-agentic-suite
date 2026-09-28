package com.msb.solana.gateway.serialization;

import org.springframework.stereotype.Component;

@Component
public class SolanaAddressValidator {

    public boolean isValid(String base58Address) {
        if (base58Address == null || base58Address.trim().isEmpty()) {
            return false;
        }
        try {
            byte[] decoded = Base58.decode(base58Address.trim());
            return decoded.length == 32;
        } catch (Exception e) {
            return false;
        }
    }

    public void validateOrThrow(String base58Address, String fieldName) {
        if (!isValid(base58Address)) {
            throw new IllegalArgumentException("Invalid Solana Base58 public key for field: " + fieldName);
        }
    }
}