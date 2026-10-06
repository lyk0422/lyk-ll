package com.example.starter.domain;

/**
 * 展示池状态：NORMAL 正常，ALARM 告警，EMERGENCY 紧急（连续3点越限），DISPOSING 处置中（暂停展示登记）。
 */
public enum ZoneStatus {
    NORMAL,
    ALARM,
    EMERGENCY,
    DISPOSING
}
