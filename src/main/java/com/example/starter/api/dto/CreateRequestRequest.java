package com.example.starter.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * 创建封锁申请请求。时间一律使用 UTC（如 {@code 2026-10-01T02:00:00Z}），
 * 时间窗为半开区间 {@code [start,end)}。
 */
public record CreateRequestRequest(
        @NotBlank String requestId,
        @NotEmpty List<@NotBlank String> sectionIds,
        @NotNull Instant start,
        @NotNull Instant end,
        @Min(0) long preBufferSeconds,
        @Min(0) long postBufferSeconds,
        @Min(0) int priority,
        @NotNull Instant businessTime
) {
}
