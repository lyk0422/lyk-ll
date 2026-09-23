package com.example.starter.rosbridge.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 网关可调参数。
 *
 * @param path               WebSocket 端点路径
 * @param graceMs           最后一个客户端取消/断连后上游订阅保留宽限期（毫秒）
 * @param retention         每个共享订阅为重连保留的最近消息条数
 * @param defaultRatePerSec 新客户端默认发送速率上限（条/秒）
 * @param defaultQueueCap   新客户端默认发送队列上限（条）
 * @param defaultTimeoutMs  Service 默认超时（毫秒）
 */
@ConfigurationProperties(prefix = "rosbridge")
public record GatewayProperties(String path, long graceMs, int retention,
                                int defaultRatePerSec, int defaultQueueCap,
                                long defaultTimeoutMs) {

    public GatewayProperties {
        if (path == null || path.isBlank()) {
            path = "/rosbridge";
        }
        if (graceMs <= 0) {
            graceMs = 5000;
        }
        if (retention <= 0) {
            retention = 256;
        }
        if (defaultRatePerSec <= 0) {
            defaultRatePerSec = 100;
        }
        if (defaultQueueCap <= 0) {
            defaultQueueCap = 64;
        }
        if (defaultTimeoutMs <= 0) {
            defaultTimeoutMs = 30000;
        }
    }
}
