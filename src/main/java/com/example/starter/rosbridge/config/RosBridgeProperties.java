package com.example.starter.rosbridge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 网关运行参数。
 *
 * @param gracePeriodMs        最后一个客户端取消/断连后上游订阅的延迟释放宽限期（毫秒）
 * @param serviceTimeoutMs     Service 调用默认超时（毫秒）
 * @param defaultRatePerSecond 每客户端默认发送速率上限（条/秒），0 表示不限速
 * @param defaultQueueCapacity 每客户端默认发送队列上限（条）
 * @param ledgerCapacity       每个共享订阅在内存中保留用于重连补发的最近消息条数
 * @param syntheticTickIntervalMs 合成适配器演示 Topic 的自动发布周期（毫秒），&lt;=0 不发布
 */
@ConfigurationProperties(prefix = "rosbridge")
public record RosBridgeProperties(
        long gracePeriodMs,
        long serviceTimeoutMs,
        int defaultRatePerSecond,
        int defaultQueueCapacity,
        int ledgerCapacity,
        long syntheticTickIntervalMs
) {
}
