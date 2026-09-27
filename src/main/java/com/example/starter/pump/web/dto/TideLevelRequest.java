package com.example.starter.pump.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 潮位上送请求。潮位按分区＋UTC观测时刻唯一。
 *
 * @param requestId       幂等键
 * @param zoneCode        流域分区代码
 * @param tideLevel       潮位（米），非负
 * @param observedAt      潮位观测时刻（UTC，ISO-8601，如 2026-09-26T00:00:00Z）
 * @param expectedVersion 期望工况版本，须与当前版本一致
 */
public record TideLevelRequest(
        @NotBlank String requestId,
        @NotBlank String zoneCode,
        @NotNull @DecimalMin(value = "0.0", message = "潮位必须非负") BigDecimal tideLevel,
        @NotNull Instant observedAt,
        @NotNull Integer expectedVersion
) {
}
