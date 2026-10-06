package com.example.starter.domain;

/**
 * 告警状态：OPEN 未升级，ESCALATED 已升级为紧急，RESOLVED 已解除。
 */
public enum AlarmStatus {
    OPEN,
    ESCALATED,
    RESOLVED
}
