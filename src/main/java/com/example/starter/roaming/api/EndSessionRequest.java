package com.example.starter.roaming.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 会话结束请求。
 *
 * @param requestId 业务幂等键
 * @param endTime   结束时刻（UTC），决定清分归属日
 */
public record EndSessionRequest(
        @NotBlank String requestId,
        @NotNull Instant endTime) {
}
