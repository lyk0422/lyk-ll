package com.example.starter.forwarder;

/**
 * 消息入库结果。
 */
public enum IngestOutcome {
    /** 已入队（在线时会立即尝试转发）。 */
    QUEUED,
    /** 相同 dedupKey 已存在，幂等忽略。 */
    DUPLICATE,
    /** 离线且 Topic 策略为 DROP，已丢弃并写审计。 */
    DROPPED_BY_POLICY,
    /** 容量不足且无可驱逐记录，已拒绝并写审计。 */
    REJECTED_CAPACITY
}
