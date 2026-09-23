package com.example.starter.lot.api;

import java.math.BigDecimal;

/**
 * 可用余额查询结果。
 */
public record BalanceView(
        String lotId,
        String unit,
        BigDecimal availableQty,
        String status
) {
}
