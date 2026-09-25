package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 单条雷达读数。readingTime 为读数时刻（Asia/Shanghai），乱序上报按此时刻归位。
 */
public record ReadingItem(
        @NotBlank String zoneCode,
        @NotNull LocalDateTime readingTime,
        @NotNull BigDecimal displacementMm) {
}
