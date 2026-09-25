package com.example.starter.dispatch.domain;

/**
 * 调度通知类型（对上级/外部监控系统的本地模拟留痕）。
 */
public enum NotificationType {

    /** 超时升级，立即通知上级。 */
    ESCALATION,
    /** 回执实际开度偏差超限，自动告警。 */
    DEVIATION_ALARM
}
