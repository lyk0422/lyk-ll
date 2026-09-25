package com.example.starter.reinsurance.api;

import com.example.starter.reinsurance.service.ClaimService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 赔案接口：报案（预提）、估损调整、最终结案（预提冲销同事务）。
 */
@RestController
@RequestMapping("/api/reinsurance/claims")
public class ClaimController {

    private final ClaimService claimService;

    public ClaimController(ClaimService claimService) {
        this.claimService = claimService;
    }

    @PostMapping
    public ResponseEntity<ClaimResponse> createClaim(@Valid @RequestBody CreateClaimRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(claimService.createClaim(request));
    }

    @PostMapping("/{claimNo}/estimate-adjustments")
    public ClaimResponse adjustEstimate(
            @PathVariable String claimNo, @Valid @RequestBody AdjustEstimateRequest request) {
        return claimService.adjustEstimate(claimNo, request);
    }

    @PostMapping("/{claimNo}/close")
    public ClaimResponse closeClaim(
            @PathVariable String claimNo, @Valid @RequestBody CloseClaimRequest request) {
        return claimService.closeClaim(claimNo, request);
    }

    @GetMapping("/{claimNo}")
    public ClaimResponse getClaim(@PathVariable String claimNo) {
        return claimService.getClaim(claimNo);
    }
}
