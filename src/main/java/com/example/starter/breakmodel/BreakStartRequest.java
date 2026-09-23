package com.example.starter.breakmodel;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 直播插播开始请求：频道内 requestId 唯一（幂等键），优先级数值越大越高。
 */
public record BreakStartRequest(
        @NotBlank String channelId,
        @NotBlank String requestId,
        @NotNull Integer priority,
        @NotNull Long startMs,
        @NotBlank String mediaDigest
) {
}
