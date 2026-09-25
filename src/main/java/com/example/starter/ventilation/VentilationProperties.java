package com.example.starter.ventilation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 通风联动服务参数。
 *
 * @param feedbackTimeoutSeconds 到位反馈超时秒数，下发时刻起算，恰满 60 秒判超时
 * @param scheduler              后台超时巡检调度配置
 */
@ConfigurationProperties(prefix = "ventilation")
public record VentilationProperties(
        long feedbackTimeoutSeconds,
        Scheduler scheduler
) {
    /**
     * @param enabled      是否启用后台巡检（测试中可关闭，改用显式触发）
     * @param fixedDelayMs 巡检间隔毫秒
     */
    public record Scheduler(boolean enabled, long fixedDelayMs) {
    }
}
