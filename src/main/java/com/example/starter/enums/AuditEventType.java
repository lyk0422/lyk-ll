package com.example.starter.enums;

/**
 * 审计事件类型。
 */
public enum AuditEventType {
    /** DROP 策略离线丢弃。 */
    DROP_POLICY,
    /** LATEST 策略覆盖旧消息。 */
    OVERWRITE_LATEST,
    /** BOUNDED 策略因条数上限驱逐最旧消息。 */
    EVICT_BOUNDED,
    /** 总容量不足，驱逐最低优先级中最旧的非 in-flight 消息。 */
    EVICT_CAPACITY,
    /** 驱逐后仍无法写入，拒绝新消息。 */
    REJECT_CAPACITY
}
