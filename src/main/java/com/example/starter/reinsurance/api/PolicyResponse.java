package com.example.starter.reinsurance.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 保单视图。
 */
public record PolicyResponse(
        String policyNo,
        String contractNo,
        BigDecimal premium,
        LocalDate effectiveDate,
        String billNo) {
}
