package com.example.starter.tf2.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 变换缓存参数（tf2.cache.*）。
 *
 * @param windowNanos 动态样本保留窗口（纳秒）：仅淘汰时间戳小于
 *                    “该边最新样本 - 窗口”的历史样本
 */
@ConfigurationProperties(prefix = "tf2.cache")
public record Tf2CacheProperties(long windowNanos) {

    public Tf2CacheProperties {
        if (windowNanos <= 0) {
            throw new IllegalArgumentException("tf2.cache.window-nanos 必须为正");
        }
    }
}
