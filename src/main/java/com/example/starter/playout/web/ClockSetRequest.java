package com.example.starter.playout.web;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 合成时钟设定请求（仅本地演示/测试用途，不连接真实设备）。
 *
 * @param time 要设定的当前时刻(UTC)
 */
public record ClockSetRequest(@NotNull Instant time) {
}
