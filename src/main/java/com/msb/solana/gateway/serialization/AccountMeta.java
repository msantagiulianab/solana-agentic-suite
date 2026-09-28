package com.msb.solana.gateway.serialization;

/**
 * A single account referenced by a Solana instruction.
 *
 * @param pubkey   32-byte account public key
 * @param signer   whether the account must sign the transaction
 * @param writable whether the account data may be modified by the instruction
 */
public record AccountMeta(byte[] pubkey, boolean signer, boolean writable) {

    public AccountMeta {
        if (pubkey == null || pubkey.length != 32) {
            throw new IllegalArgumentException("Account public key must be 32 bytes");
        }
        pubkey = pubkey.clone();
    }
}
