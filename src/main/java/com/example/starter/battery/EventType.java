package com.example.starter.battery;

/**
 * 电池流转事件类型，事件按序构成哈希链。
 */
public enum EventType {
    /** 建档。 */
    REGISTER,
    /** 入站。 */
    INBOUND,
    /** 检测（结论不可修改，复检走新批次）。 */
    INSPECTION,
    /** 上架可换。 */
    LISTED,
    /** 换出绑定车辆。 */
    SWAP_OUT,
    /** 站间调拨发出（在途）。 */
    TRANSFER_OUT,
    /** 到站签收。 */
    TRANSFER_SIGN,
    /** 在途超 24 小时自动转调拨异常。 */
    TRANSFER_EXCEPTION,
    /** 异常人工核销。 */
    WRITE_OFF,
    /** 链不一致等异常告警。 */
    ALARM
}
