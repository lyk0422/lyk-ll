package com.example.starter.breakmodel;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 直播插播结束请求：requestId 为幂等键，endMs 为结束时刻（UTC 毫秒）。
 */
public record BreakEndRequest(
        @NotBlank String channelId,
        @NotBlank String requestId,
        @NotNull Long endMs
) {
}
