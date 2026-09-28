package com.msb.solana.gateway.controller;

import com.msb.solana.gateway.model.SettlementResult;
import com.msb.solana.gateway.service.ChannelSettlementService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Administrative endpoint for triggering an on-chain settlement sweep of a
 * payment channel's cumulative verified amount.
 */
@RestController
@RequestMapping("/api/v1/settlement")
public class SettlementController {

    private final ChannelSettlementService settlementService;

    public SettlementController(ChannelSettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @PostMapping("/channels/{channelId}/sweep")
    public ResponseEntity<?> sweepChannel(@PathVariable String channelId) {
        try {
            SettlementResult result = settlementService.settleChannel(channelId);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "CHANNEL_NOT_FOUND_OR_EMPTY", "message", ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "CHANNEL_ALREADY_SETTLED", "message", ex.getMessage()));
        }
    }
}