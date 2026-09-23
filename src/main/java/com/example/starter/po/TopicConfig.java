package com.example.starter.po;

import java.time.Instant;

/**
 * Topic 离线策略配置持久化对象。
 *
 * @param id         自增主键
 * @param topic      Topic 名称，全局唯一
 * @param policyType 策略：DROP/LATEST/FIFO/BOUNDED
 * @param capacity   BOUNDED 策略容量（条），其余策略为 null
 * @param createdAt  创建时间
 * @param updatedAt  更新时间
 */
public record TopicConfig(Long id, String topic, String policyType, Integer capacity,
                          Instant createdAt, Instant updatedAt) {
}
