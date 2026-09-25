package com.example.starter.gas.model;

/**
 * 站内事件类型。
 * ODOR_ALARM：浓度越限告警；VALVE_LOCKED：双人确认锁阀；VALVE_REOPENED：双人确认复开并解除告警；
 * LATE_DETECTION：封存后迟到检测；MANUAL_REVIEW：日结转人工。
 */
public enum EventKind {
    ODOR_ALARM,
    VALVE_LOCKED,
    VALVE_REOPENED,
    LATE_DETECTION,
    MANUAL_REVIEW
}
