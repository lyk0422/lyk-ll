package com.example.starter.rosbridge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * rosbridge 网关配置，前缀 {@code rosbridge}。
 */
@ConfigurationProperties(prefix = "rosbridge")
public class RosbridgeProperties {

    /** WebSocket 握手路径。 */
    private String path = "/ws";

    /** 断连后订阅与游标保留宽限期（毫秒）；宽限期内同 clientId 重连可续投。 */
    private long reconnectGraceMs = 30000;

    /** 单客户端默认速率上限（Topic 消息条/秒），0 表示不限速。 */
    private int defaultRateLimitPerSecond = 0;

    /** 单客户端默认发送队列上限（条）。 */
    private int defaultSendQueueCapacity = 64;

    /** Service 调用默认超时（毫秒）。 */
    private long serviceTimeoutMs = 5000;

    /** 每个 Topic 在内存库中保留的最近消息条数上限，供重连续投。 */
    private int messageRetentionPerTopic = 4096;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public long getReconnectGraceMs() {
        return reconnectGraceMs;
    }

    public void setReconnectGraceMs(long reconnectGraceMs) {
        this.reconnectGraceMs = reconnectGraceMs;
    }

    public int getDefaultRateLimitPerSecond() {
        return defaultRateLimitPerSecond;
    }

    public void setDefaultRateLimitPerSecond(int defaultRateLimitPerSecond) {
        this.defaultRateLimitPerSecond = defaultRateLimitPerSecond;
    }

    public int getDefaultSendQueueCapacity() {
        return defaultSendQueueCapacity;
    }

    public void setDefaultSendQueueCapacity(int defaultSendQueueCapacity) {
        this.defaultSendQueueCapacity = defaultSendQueueCapacity;
    }

    public long getServiceTimeoutMs() {
        return serviceTimeoutMs;
    }

    public void setServiceTimeoutMs(long serviceTimeoutMs) {
        this.serviceTimeoutMs = serviceTimeoutMs;
    }

    public int getMessageRetentionPerTopic() {
        return messageRetentionPerTopic;
    }

    public void setMessageRetentionPerTopic(int messageRetentionPerTopic) {
        this.messageRetentionPerTopic = messageRetentionPerTopic;
    }
}
