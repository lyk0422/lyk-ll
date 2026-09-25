package com.example.starter.gas.web.dto;

import java.math.BigDecimal;

/**
 * 门站当前状态视图。
 */
public record StationResponse(
        String stationCode,
        String stationName,
        BigDecimal odorTarget,
        BigDecimal odorTolerance,
        BigDecimal pressureLow,
        BigDecimal pressureHigh,
        BigDecimal odorConsumed,
        String valveState,
        boolean alarmActive,
        int violationCount
) {
}
