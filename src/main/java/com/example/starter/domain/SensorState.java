package com.example.starter.domain;

/**
 * 传感器状态：ONLINE 在线，MISSING 失联数据缺失（挂起自动处置），PENDING_CONFIRM 复连待人工确认补算。
 */
public enum SensorState {
    ONLINE,
    MISSING,
    PENDING_CONFIRM
}
