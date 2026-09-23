package com.example.starter.playout.web;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

/**
 * 直播插播结束请求；end 省略时使用合成时钟当前时刻。
 *
 * @param channel   频道标识
 * @param requestId 插播请求幂等键
 * @param end       插播结束时间(UTC)，可空
 */
public record LiveEndRequest(
        @NotBlank String channel,
        @NotBlank String requestId,
        Instant end) {
}
