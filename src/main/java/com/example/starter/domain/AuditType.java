package com.example.starter.domain;

/**
 * 审计事件类型。所有策略丢弃、覆盖、驱逐、拒绝与重试都必须显式记录，不能伪装成已转发。
 */
public enum AuditType {
    /** DROP 策略下离线消息被直接丢弃。 */
    DROP_POLICY,
    /** LATEST 策略下旧的待发消息被新消息覆盖。 */
    OVERWRITE,
    /** BOUNDED 策略容量用尽时，最旧待发消息被新消息挤出。 */
    BOUNDED_OVERFLOW,
    /** 容量不足时按最低优先级、最旧顺序驱逐的待发消息。 */
    EVICT,
    /** 驱逐后仍无法写入而拒绝的新消息。 */
    REJECT,
    /** 消息已发送给上游，等待 ACK。 */
    SENT,
    /** 收到明确 ACK，消息投递完成。 */
    DELIVERED,
    /** 重复或乱序到达的 ACK，未改变任何计数。 */
    ACK_IGNORED,
    /** in-flight 超时或服务重启后重新置为待发。 */
    RETRY_RESET,
    /** 相同 dedupKey 的待发记录已存在，本次写入幂等跳过。 */
    DEDUP_SKIP
}
