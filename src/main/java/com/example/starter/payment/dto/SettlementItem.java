package com.example.starter.payment.dto;

import java.math.BigDecimal;

/**
 * 单个商户清算结果。settledAmount = totalAmount - totalFee。
 */
public record SettlementItem(
        String merchantNo,
        int txnCount,
        BigDecimal totalAmount,
        BigDecimal totalFee,
        BigDecimal settledAmount) {
}
