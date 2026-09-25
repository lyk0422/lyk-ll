package com.example.starter.battery;

/**
 * 电池资产状态。
 */
public enum BatteryStatus {
    /** 在库待检（入站后未检测）。 */
    PENDING_INSPECTION,
    /** 充电/待修（检测不达标）。 */
    CHARGING,
    /** 上架可换（检测达标）。 */
    LISTED,
    /** 换出在用（车辆侧）。 */
    IN_USE,
    /** 站间调拨在途。 */
    IN_TRANSIT,
    /** 调拨异常（在途超 24 小时，待人工核销）。 */
    TRANSFER_EXCEPTION
}
