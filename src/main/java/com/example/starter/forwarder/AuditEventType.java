package com.example.starter.forwarder;

/**
 * 审计事件类型。策略造成的丢弃与覆盖必须落审计，不能伪装成已转发。
 */
public enum AuditEventType {
    /** 离线时按 Topic 策略直接丢弃（DROP 或 BOUNDED 挤出最旧一条）。 */
    DROPPED_BY_POLICY,
    /** LATEST 策略下被更新消息覆盖。 */
    OVERWRITTEN,
    /** 全局容量不足时被驱逐（最低优先级中最旧且非 in-flight）。 */
    EVICTED,
    /** 容量不足且无可驱逐记录，新消息被拒绝。 */
    REJECTED_CAPACITY,
    /** 已发送到上游，等待 ACK。 */
    SENT,
    /** 收到明确 ACK，不再转发。 */
    ACKED,
    /** 服务重启后将未确认的 in-flight 消息恢复为待补发。 */
    RECOVERED,
    /** in-flight 超时未确认，重新排队等待重试。 */
    REQUEUED
}
