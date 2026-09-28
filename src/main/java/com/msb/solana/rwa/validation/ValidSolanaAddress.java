package com.msb.solana.rwa.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bean Validation constraint ensuring a string is a syntactically valid
 * Solana base58 public key (32-44 base58 characters, no {@code 0/O/I/l}).
 */
@Documented
@Constraint(validatedBy = SolanaAddressValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidSolanaAddress {

    String message() default "must be a valid Solana base58 address (32-44 characters)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
