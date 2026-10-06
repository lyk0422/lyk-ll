package com.example.starter.transition.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 实际掺配读数视图。
 */
public record BlendReadingView(
        String orderNo,
        Integer versionNo,
        LocalDateTime sampledAt,
        BigDecimal actualRatioPct,
        BigDecimal deviationPct,
        boolean outOfTolerance) {
}
