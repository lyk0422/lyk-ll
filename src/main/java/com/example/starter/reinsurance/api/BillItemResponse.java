package com.example.starter.reinsurance.api;

import java.math.BigDecimal;

/**
 * 账单明细视图；{@code amount} 必须可用 {@code grossAmount × cessionRate}（受限额时备注）复算解释。
 */
public record BillItemResponse(
        Integer lineNo,
        String itemType,
        String refNo,
        String refBillNo,
        BigDecimal grossAmount,
        BigDecimal cessionRate,
        BigDecimal amount,
        String remark) {
}
