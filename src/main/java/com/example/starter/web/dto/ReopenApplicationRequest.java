package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 复开申请请求。
 *
 * @param requestId   幂等请求标识
 * @param requestedAt 申请判定时刻（UTC），据此检查风速回落持续时长
 */
public record ReopenApplicationRequest(
        @NotBlank String requestId,
        @NotNull Instant requestedAt) {
}
