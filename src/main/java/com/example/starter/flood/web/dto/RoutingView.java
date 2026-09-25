package com.example.starter.flood.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 调洪演算结果视图：含输入、库容反查结果、双指标判定与综合判定、判定口径。
 */
public record RoutingView(
        String routingNo,
        String warningNo,
        String pondCode,
        BigDecimal currentWaterLevel,
        BigDecimal currentStorage,
        BigDecimal maxStorage,
        BigDecimal availableCapacity,
        BigDecimal designFloodVolume,
        String capacityJudgment,
        BigDecimal dryBeachLength,
        BigDecimal phreaticDepth,
        String dryBeachJudgment,
        String phreaticJudgment,
        String dangerJudgment,
        String inputSnapshot,
        String criterion,
        boolean replay,
        LocalDateTime createdAt) {
}
