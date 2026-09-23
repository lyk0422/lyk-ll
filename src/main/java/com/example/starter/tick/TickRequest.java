package com.example.starter.tick;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 调度 tick 请求：按频道在指定 UTC 时刻结算并生成 as-run 记录。
 */
public record TickRequest(
        @NotBlank String channelId,
        @NotNull Long atMs
) {
}
