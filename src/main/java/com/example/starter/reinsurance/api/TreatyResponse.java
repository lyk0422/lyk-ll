package com.example.starter.reinsurance.api;

import java.math.BigDecimal;

/**
 * 合约视图。
 */
public record TreatyResponse(
        String contractNo,
        BigDecimal cessionRate,
        BigDecimal perPolicyLimit) {
}
