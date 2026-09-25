package com.example.starter.gas.web.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 压力链复算结果：逐点核对恰等限值与 RAISE/LOWER/HOLD 判定是否与落库一致。
 */
public record PressureChainReplayResponse(
        String stationCode,
        String settleDate,
        BigDecimal lowKpa,
        BigDecimal highKpa,
        long regulatedCount,
        List<PressureChainPoint> points
) {
}
