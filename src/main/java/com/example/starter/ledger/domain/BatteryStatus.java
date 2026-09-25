package com.example.starter.ledger.domain;

/**
 * 电池资产状态。
 */
public enum BatteryStatus {
    /** 已建档，尚未入站。 */
    REGISTERED,
    /** 已入站（在站点、待检测）。 */
    AT_STATION,
    /** 检测合格，已上架可换。 */
    AVAILABLE,
    /** 检测不合格，充电/待修。 */
    CHARGING,
    /** 已换出，车辆侧在用。 */
    IN_USE,
    /** 站间调拨在途。 */
    IN_TRANSIT,
    /** 调拨异常（在途超过 24 小时），待人工核销。 */
    TRANSFER_EXCEPTION,
    /** 已人工核销，终态。 */
    WRITTEN_OFF
}
