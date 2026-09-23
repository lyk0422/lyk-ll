package com.example.starter.forwarder.config;

import com.example.starter.forwarder.policy.RetentionPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 转发器配置属性（forwarder.*）。
 */
@ConfigurationProperties(prefix = "forwarder")
public class ForwarderProperties {

    /** 待发 + in-flight 消息总容量上限（条数）。 */
    private int capacity = 10000;
    /** 单次补发批量。 */
    private int dispatchBatchSize = 200;
    /** in-flight ACK 超时毫秒。 */
    private long ackTimeoutMs = 5000;
    /** 调度器轮询间隔毫秒。 */
    private long dispatchIntervalMs = 500;
    /** 启动时是否默认联网。 */
    private boolean onlineOnStartup = false;
    /** 各 Topic 的离线保留策略配置。 */
    private List<TopicConfig> topics = List.of();

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public int getDispatchBatchSize() {
        return dispatchBatchSize;
    }

    public void setDispatchBatchSize(int dispatchBatchSize) {
        this.dispatchBatchSize = dispatchBatchSize;
    }

    public long getAckTimeoutMs() {
        return ackTimeoutMs;
    }

    public void setAckTimeoutMs(long ackTimeoutMs) {
        this.ackTimeoutMs = ackTimeoutMs;
    }

    public long getDispatchIntervalMs() {
        return dispatchIntervalMs;
    }

    public void setDispatchIntervalMs(long dispatchIntervalMs) {
        this.dispatchIntervalMs = dispatchIntervalMs;
    }

    public boolean isOnlineOnStartup() {
        return onlineOnStartup;
    }

    public void setOnlineOnStartup(boolean onlineOnStartup) {
        this.onlineOnStartup = onlineOnStartup;
    }

    public List<TopicConfig> getTopics() {
        return topics;
    }

    public void setTopics(List<TopicConfig> topics) {
        this.topics = topics;
    }

    /**
     * 单个 Topic 的策略配置。
     */
    public static class TopicConfig {
        private String topic;
        private String policy;

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }

        public String getPolicy() {
            return policy;
        }

        public void setPolicy(String policy) {
            this.policy = policy;
        }
    }

    /**
     * 解析为 topic -> 策略映射；未配置的 Topic 默认 FIFO。
     */
    public Map<String, RetentionPolicy> policyMap() {
        Map<String, RetentionPolicy> map = new LinkedHashMap<>();
        for (TopicConfig config : topics) {
            map.put(config.getTopic(), RetentionPolicy.parse(config.getPolicy()));
        }
        return map;
    }
}
