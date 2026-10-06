package com.example.starter.domain;

/**
 * 展示池事件类型，用于日评估复算告警处置链与稳定时长。
 */
public enum EventType {
    /** 池状态变更 */
    STATUS_CHANGE,
    /** 告警产生 */
    ALARM_RAISED,
    /** 处置指令下发 */
    COMMAND_ISSUED,
    /** 处置回执确认 */
    COMMAND_CONFIRMED,
    /** 回执超时升级 */
    COMMAND_ESCALATED,
    /** 传感器失联，数据缺失 */
    SENSOR_MISSING,
    /** 传感器复连，待人工确认 */
    SENSOR_RECONNECTED,
    /** 人工确认复连并补算 */
    SENSOR_CONFIRMED,
    /** 复测合格恢复 */
    RECOVERY
}
