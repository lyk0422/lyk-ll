package com.example.starter.domain;

/**
 * 偏差告警状态。
 */
public enum AlarmStatus {
    /** 触发后待确认。 */
    ACTIVE,
    /** 已以 requestId 幂等确认。 */
    ACKNOWLEDGED
}
