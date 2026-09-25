package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 创建测区。速率单位：warnHourlyRate/safetyHourlyRate 为毫米/小时，alarmDailyRate 为毫米/日。
 */
public record CreateZoneRequest(
        @NotBlank String zoneCode,
        @NotBlank String zoneName,
        @NotNull BigDecimal warnHourlyRate,
        @NotNull BigDecimal alarmDailyRate,
        @NotNull BigDecimal safetyHourlyRate) {
}
