package com.example.starter.greenhouse.domain;

/**
 * 偏差告警状态：OPEN 连续 3 点偏离触发后待确认；ACKNOWLEDGED 已以 requestId 幂等确认。
 */
public enum AlertStatus {
    OPEN,
    ACKNOWLEDGED
}
