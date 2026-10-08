package com.example.starter.water;

/**
 * 传感器状态：ONLINE 在线；LOST 失联（恰满 10 分钟无心跳）；
 * AWAITING_CONFIRM 已复连待人工确认补算。
 */
public enum SensorStatus {
    ONLINE,
    LOST,
    AWAITING_CONFIRM
}
