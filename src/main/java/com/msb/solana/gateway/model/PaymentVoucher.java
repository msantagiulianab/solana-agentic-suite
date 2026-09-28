package com.msb.solana.gateway.model;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public record PaymentVoucher(
    String channelId,
    String payerPubkey,
    long cumulativeAmountAtomic,
    long nonce,
    String signature
) {
    /**
     * Packs the voucher state into canonical deterministic bytes for Ed25519 verification:
     * [DomainTag: "X402_CHANNEL_V1"] || [ChannelIdBytes (variable length + len prefix)] 
     * || [CumulativeAmount (8 bytes little-endian)] || [Nonce (8 bytes little-endian)]
     */
    public byte[] getCanonicalPayload() {
        byte[] domainTag = "X402_CHANNEL_V1:".getBytes(StandardCharsets.UTF_8);
        byte[] channelBytes = channelId.getBytes(StandardCharsets.UTF_8);
        
        ByteBuffer buffer = ByteBuffer.allocate(domainTag.length + 2 + channelBytes.length + 8 + 8)
                .order(ByteOrder.LITTLE_ENDIAN);
        
        buffer.put(domainTag);
        buffer.putShort((short) channelBytes.length);
        buffer.put(channelBytes);
        buffer.putLong(cumulativeAmountAtomic);
        buffer.putLong(nonce);
        
        return buffer.array();
    }
}