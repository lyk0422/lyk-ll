package com.example.starter.gas.web.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 浓度曲线复算结果：可逐点核对合格判定、连续越限计数与锁阀点是否与落库一致。
 */
public record ConcentrationReplayResponse(
        String stationCode,
        String settleDate,
        BigDecimal targetMg,
        BigDecimal lowerMg,
        BigDecimal upperMg,
        int lockPointCount,
        List<ConCurvePoint> points
) {
}
