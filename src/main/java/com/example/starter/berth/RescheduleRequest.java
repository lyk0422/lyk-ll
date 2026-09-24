package com.example.starter.berth;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 已批准窗口改期请求：视为新申请，携带新的幂等键与新的靠泊起止时刻（UTC）。
 */
public record RescheduleRequest(
        @NotBlank String requestId,
        @NotNull Instant startTime,
        @NotNull Instant endTime) {
}
