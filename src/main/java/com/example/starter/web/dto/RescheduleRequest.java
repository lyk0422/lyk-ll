package com.example.starter.web.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotNull;

/**
 * 改期请求：新的预计靠泊起止时刻（UTC，半开区间）。
 */
public record RescheduleRequest(
        @NotNull Instant startAt,
        @NotNull Instant endAt) {
}
