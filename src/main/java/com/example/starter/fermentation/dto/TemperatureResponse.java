package com.example.starter.fermentation.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 温度采样受理结果：目标温度、偏离量、连续偏离点数与是否告警，支持口径复算。
 */
public record TemperatureResponse(
        String requestId,
        String batchNo,
        Instant readingAt,
        BigDecimal targetTemp,
        BigDecimal temperature,
        BigDecimal deviation,
        boolean outOfRange,
        int consecutiveOutOfRange,
        boolean alarmed,
        boolean late) {
}
