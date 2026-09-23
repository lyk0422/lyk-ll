package com.example.starter.forwarder;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 转发器配置。
 */
@ConfigurationProperties(prefix = "forwarder")
public class ForwarderProperties {

    /** 全局待发 + in-flight 容量上限。 */
    private int capacity = 1000;

    /** in-flight 超时秒数，超时未确认允许重试。 */
    private long inFlightTimeoutSeconds = 30;

    /** 合成上游发送失败概率（0~1），模拟网络抖动。 */
    private double upstreamFailureRate = 0.0;

    /** 启动时是否在线。 */
    private boolean networkOnline = true;

    /** 未配置 Topic 的默认策略。 */
    private String defaultPolicy = "FIFO";

    /** Topic -> 策略文本（DROP/LATEST/FIFO/BOUNDED(n)）。 */
    private Map<String, String> topicPolicies = new LinkedHashMap<>();

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public long getInFlightTimeoutSeconds() {
        return inFlightTimeoutSeconds;
    }

    public void setInFlightTimeoutSeconds(long inFlightTimeoutSeconds) {
        this.inFlightTimeoutSeconds = inFlightTimeoutSeconds;
    }

    public double getUpstreamFailureRate() {
        return upstreamFailureRate;
    }

    public void setUpstreamFailureRate(double upstreamFailureRate) {
        this.upstreamFailureRate = upstreamFailureRate;
    }

    public boolean isNetworkOnline() {
        return networkOnline;
    }

    public void setNetworkOnline(boolean networkOnline) {
        this.networkOnline = networkOnline;
    }

    public String getDefaultPolicy() {
        return defaultPolicy;
    }

    public void setDefaultPolicy(String defaultPolicy) {
        this.defaultPolicy = defaultPolicy;
    }

    public Map<String, String> getTopicPolicies() {
        return topicPolicies;
    }

    public void setTopicPolicies(Map<String, String> topicPolicies) {
        this.topicPolicies = topicPolicies;
    }

    /**
     * 查询指定 Topic 的策略，未配置时返回默认策略。
     */
    public TopicPolicy policyFor(String topic) {
        String text = topicPolicies.getOrDefault(topic, defaultPolicy);
        return TopicPolicy.parse(text);
    }
}
