package com.msb.solana.gateway.serialization;

import java.util.List;

/**
 * A single Solana instruction composed of a program id, a list of accounts,
 * and the instruction data payload.
 *
 * @param programId 32-byte program public key
 * @param accounts  ordered account metas required by the instruction
 * @param data      opaque instruction data (discriminator + args)
 */
public record SolanaInstruction(byte[] programId, List<AccountMeta> accounts, byte[] data) {

    public SolanaInstruction {
        if (programId == null || programId.length != 32) {
            throw new IllegalArgumentException("Program id must be 32 bytes");
        }
        programId = programId.clone();
        accounts = List.copyOf(accounts);
        data = data == null ? new byte[0] : data.clone();
    }
}
