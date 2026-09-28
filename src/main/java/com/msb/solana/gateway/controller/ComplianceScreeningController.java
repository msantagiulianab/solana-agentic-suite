package com.msb.solana.gateway.controller;

import com.msb.solana.gateway.compliance.AddressRiskEvaluator;
import com.msb.solana.gateway.compliance.ScreenAddressRequest;
import com.msb.solana.gateway.compliance.ScreeningResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/compliance")
public class ComplianceScreeningController {

    private final AddressRiskEvaluator addressRiskEvaluator;

    public ComplianceScreeningController(AddressRiskEvaluator addressRiskEvaluator) {
        this.addressRiskEvaluator = addressRiskEvaluator;
    }

    @PostMapping("/screen-address")
    public ResponseEntity<ScreeningResult> screenAddress(@RequestBody ScreenAddressRequest request) {
        ScreeningResult result = addressRiskEvaluator.evaluate(request.address());
        return ResponseEntity.ok(result);
    }
}