package com.msb.solana.gateway.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PaymentSettlementReceipt(
    @JsonProperty("channelId") String channelId,
    @JsonProperty("settledAmountAtomic") long settledAmountAtomic,
    @JsonProperty("nonce") long nonce,
    @JsonProperty("timestamp") long timestamp,
    @JsonProperty("status") String status
) {}