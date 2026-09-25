package com.example.starter.roaming.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 清明细行：批内每条已结束会话的跨运营商计费结果。
 */
public record DetailRow(
        Long id,
        Long batchId,
        String sessionNo,
        String homeOperator,
        String chargingOperator,
        long energyWh,
        BigDecimal receivableAmount,
        BigDecimal payableAmount,
        LocalDateTime createdAt) {
}
