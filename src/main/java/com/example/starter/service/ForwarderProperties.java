package com.example.starter.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 转发器配置。
 *
 * @param capacity          全局存活消息容量上限（条）
 * @param inFlightTimeoutMs in-flight 超时时间（毫秒），超时后重新允许补发
 * @param flushBatchSize    单次调度最多补发条数
 */
@ConfigurationProperties(prefix = "forwarder")
public record ForwarderProperties(long capacity, long inFlightTimeoutMs, int flushBatchSize) {

    public ForwarderProperties {
        if (capacity <= 0) {
            capacity = 1000;
        }
        if (inFlightTimeoutMs <= 0) {
            inFlightTimeoutMs = 30_000;
        }
        if (flushBatchSize <= 0) {
            flushBatchSize = 100;
        }
    }
}
