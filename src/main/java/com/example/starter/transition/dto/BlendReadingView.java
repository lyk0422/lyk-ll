package com.example.starter.transition.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 实际掺配读数视图：含偏差、合格判定与登记后控制状态。
 */
public record BlendReadingView(
        String orderNo,
        Integer versionNo,
        LocalDateTime sampledAt,
        BigDecimal actualRatioPct,
        BigDecimal deviationPct,
        Boolean withinTolerance,
        Boolean outOfControl,
        String controlStatus) {
}
