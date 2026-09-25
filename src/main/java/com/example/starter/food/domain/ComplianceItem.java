package com.example.starter.food.domain;

import java.math.BigDecimal;

/**
 * 逐项判定明细。itemType=ADDITIVE 为单项限量判定；itemType=FUNCTION_GROUP 为同功能添加剂并用比例判定。
 */
public record ComplianceItem(
        String itemType,
        String additiveCode,
        String additiveName,
        String functionType,
        String source,
        BigDecimal directAmount,
        BigDecimal carryOverAmount,
        BigDecimal effectiveAmount,
        BigDecimal maxAmount,
        BigDecimal ratio,
        boolean pass,
        String message) {
}
