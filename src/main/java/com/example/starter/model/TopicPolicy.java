package com.example.starter.model;

import com.example.starter.enums.RetentionStrategy;

/**
 * Topic 离线保留策略配置。
 */
public class TopicPolicy {

    private Long id;
    /** 消息主题，全局唯一。 */
    private String topic;
    /** 保留策略。 */
    private RetentionStrategy strategy;
    /** BOUNDED 策略的保留条数 n，其余策略为 null。单位：条。 */
    private Integer bound;
    /** 创建时间，Unix 毫秒时间戳（Asia/Shanghai）。 */
    private Long createdAt;
    /** 最近更新时间，Unix 毫秒时间戳（Asia/Shanghai）。 */
    private Long updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public RetentionStrategy getStrategy() {
        return strategy;
    }

    public void setStrategy(RetentionStrategy strategy) {
        this.strategy = strategy;
    }

    public Integer getBound() {
        return bound;
    }

    public void setBound(Integer bound) {
        this.bound = bound;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
