package com.msb.solana.rwa.rpc.dto;

/**
 * Immutable, index-aligned finality result for a single transaction signature.
 *
 * <p>Unlike the raw {@link SignatureStatus} element, this record carries the
 * requested signature back alongside its on-chain status so callers can correlate
 * results without relying on positional indexes. The {@link #err()} payload is
 * intentionally typed {@link Object} because Solana nodes emit transaction errors
 * as a JSON object ({@code {"InstructionError":[...]}}), a string, or {@code null}.
 *
 * @param signature          base58 transaction signature
 * @param slot               slot at which the transaction was observed
 * @param confirmations      block confirmations (null once finalized)
 * @param confirmationStatus commitment: {@code "processed"}, {@code "confirmed"}, or {@code "finalized"}
 * @param err                transaction error payload (null on success)
 */
public record SignatureStatusResult(
        String signature,
        Long slot,
        Long confirmations,
        String confirmationStatus,
        Object err) {

    /**
     * Maps a raw RPC status element onto the requested signature, or an
     * "unconfirmed" sentinel when the node returned no value for it.
     */
    public static SignatureStatusResult from(String signature, SignatureStatus status) {
        if (status == null) {
            return new SignatureStatusResult(signature, null, null, null, null);
        }
        return new SignatureStatusResult(
                signature,
                status.slot(),
                status.confirmations(),
                status.confirmationStatus(),
                status.err());
    }

    /**
     * @return true when the transaction reached the irreversible finalized
     *         commitment and reported no execution error
     */
    public boolean isFinalized() {
        return "finalized".equalsIgnoreCase(confirmationStatus) && err == null;
    }

    /**
     * @return true when the node reported an on-chain transaction execution error
     */
    public boolean hasError() {
        return err != null;
    }

    /**
     * @return true when the transaction is still only at the confirmed commitment
     */
    public boolean isConfirmed() {
        return "confirmed".equalsIgnoreCase(confirmationStatus);
    }
}
