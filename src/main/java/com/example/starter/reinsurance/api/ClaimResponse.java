package com.example.starter.reinsurance.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 赔案及摊回视图。
 */
public record ClaimResponse(
        String claimNo,
        String policyNo,
        String status,
        BigDecimal estimatedAmount,
        BigDecimal paidAmount,
        LocalDate lossDate,
        BigDecimal provisionAmount,
        BigDecimal settledAmount,
        BigDecimal cappedExcess,
        BigDecimal accountBalance) {
}
