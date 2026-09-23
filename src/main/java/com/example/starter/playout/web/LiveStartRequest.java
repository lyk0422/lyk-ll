package com.example.starter.playout.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 直播插播开始请求。
 *
 * @param channel  频道标识
 * @param requestId 插播请求幂等键（同频道唯一）
 * @param start    插播开始时间(UTC)
 * @param priority 优先级，整数越大越高
 */
public record LiveStartRequest(
        @NotBlank String channel,
        @NotBlank String requestId,
        @NotNull Instant start,
        @NotNull Integer priority) {
}
