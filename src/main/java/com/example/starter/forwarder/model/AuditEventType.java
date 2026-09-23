package com.example.starter.forwarder.model;

/**
 * 审计事件类型，所有策略丢弃/覆盖/驱逐/拒绝都必须留痕，不得伪装成已转发。
 */
public enum AuditEventType {
    /** DROP 策略下离线消息被直接丢弃。 */
    DROP_POLICY,
    /** LATEST 策略下旧消息被新消息覆盖。 */
    OVERWRITE_LATEST,
    /** BOUNDED(n) 策略容量满，最旧的一条待发消息被挤出。 */
    EVICT_BOUNDED,
    /** 全局容量不足，最低优先级中最旧且非 in-flight 的记录被驱逐。 */
    EVICT_CAPACITY,
    /** 驱逐后仍无法写入，新消息被拒绝。 */
    REJECT_CAPACITY,
    /** in-flight 超时，消息回到待发重试。 */
    TIMEOUT_RETRY,
    /** 服务启动时发现遗留 in-flight 记录，重置为待发。 */
    RESET_INFLIGHT,
    /** 消息被发送给上游适配器。 */
    DISPATCH,
    /** 收到 ACK 并确认了一条消息。 */
    ACK,
    /** 收到重复或无法匹配的 ACK，仅记录不改计数。 */
    ACK_DUPLICATE
}
