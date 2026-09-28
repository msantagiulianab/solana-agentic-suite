package com.msb.solana.rwa.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Base58 validation for Solana addresses. The canonical alphabet excludes
 * {@code 0}, {@code O}, {@code I}, and {@code l}; Solana public keys are
 * 32 bytes, which base58-encodes to 32-44 characters.
 *
 * <p>Null values pass through here and are enforced by {@code @NotBlank} on
 * the DTO field, keeping the constraint composable.
 */
public class SolanaAddressValidator implements ConstraintValidator<ValidSolanaAddress, String> {

    private static final String BASE58_ALPHABET =
            "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

    private static final int MIN_LENGTH = 32;
    private static final int MAX_LENGTH = 44;

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        if (value.length() < MIN_LENGTH || value.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (BASE58_ALPHABET.indexOf(value.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }
}
