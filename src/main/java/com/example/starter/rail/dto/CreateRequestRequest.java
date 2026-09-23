package com.example.starter.rail.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * 创建封锁申请请求。时间以 UTC ISO-8601 时刻提交，半开区间 [start,end)。
 */
public record CreateRequestRequest(
        @NotBlank String requestId,
        @NotEmpty List<@NotBlank String> sectionIds,
        @NotNull Instant start,
        @NotNull Instant end,
        @Min(0) long bufferBeforeMs,
        @Min(0) long bufferAfterMs,
        @Min(0) int priority
) {
}
