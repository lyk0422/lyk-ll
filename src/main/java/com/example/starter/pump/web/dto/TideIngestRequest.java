package com.example.starter.pump.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 分区潮位上送请求：按分区+UTC采集时刻唯一。
 *
 * @param requestId   幂等键
 * @param zoneCode    流域分区代码
 * @param readingTime 潮位采集时刻（UTC，带偏移的ISO-8601，如 2026-09-26T00:00:00Z）
 * @param level       潮位（米，非负）
 */
public record TideIngestRequest(
        @NotBlank String requestId,
        @NotBlank String zoneCode,
        @NotNull OffsetDateTime readingTime,
        @NotNull @DecimalMin(value = "0.0", message = "潮位必须非负") BigDecimal level
) {
}
