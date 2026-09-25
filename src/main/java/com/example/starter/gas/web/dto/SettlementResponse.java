package com.example.starter.gas.web.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 日结响应。偏差 |实耗-理论|/理论 恰 0.03 合格封存；大于 0.03 转人工。
 * SEALED 为终态；重复封存幂等返回首次结果。
 */
public record SettlementResponse(
        String requestId,
        String stationCode,
        String settleDate,
        BigDecimal totalFlowM3,
        BigDecimal theoreticalConsumeG,
        BigDecimal actualConsumeG,
        BigDecimal deviationRatio,
        BigDecimal toleranceRatio,
        boolean withinTolerance,
        String status,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime sealedAt,
        boolean idempotentReplayed
) {
}
