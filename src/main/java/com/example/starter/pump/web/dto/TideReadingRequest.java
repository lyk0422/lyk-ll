package com.example.starter.pump.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 潮位上送：按“分区+UTC时刻”唯一，自增ID即潮位版本。
 *
 * @param requestId 幂等键
 * @param zoneCode  流域分区代码
 * @param tideTime  潮位采集UTC时刻（带偏移，按UTC时刻唯一）
 * @param tideLevel 潮位（米，非负）
 */
public record TideReadingRequest(
        @NotBlank String requestId,
        @NotBlank String zoneCode,
        @NotNull OffsetDateTime tideTime,
        @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal tideLevel
) {
}
