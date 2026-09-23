package com.example.starter.playout.web;

import jakarta.validation.constraints.NotBlank;

/**
 * 调度 tick 请求；time 省略时使用合成时钟当前时刻。
 *
 * @param channel 频道标识
 * @param time    结算时刻(UTC)，可空
 */
public record TickRequest(@NotBlank String channel, java.time.Instant time) {
}
