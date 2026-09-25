package com.example.starter.roaming.web.dto;

import java.math.BigDecimal;

/**
 * 清明细：单笔跨运营商会话的应收/应付。
 */
public record ClearingDetailDto(
        String sessionNo,
        String homeOperator,
        String chargingOperator,
        long energyWh,
        BigDecimal receivableAmount,
        BigDecimal payableAmount) {
}
