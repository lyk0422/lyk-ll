package com.example.starter.atm.domain;

/**
 * 加钞计划状态机：
 * PREPARED 预测编制完成
 * APPROVED 审批通过（押运计时开始，时限 4 小时）
 * IN_TRANSIT 押运中（首名押运员已打卡）
 * ESCORT_DONE 两名不同押运员先后打卡完成
 * COUNTED 双人清点完成
 * DEVICE_CONFIRMED 设备入账确认完成
 * RECONCILED 三方一致核销（终态之一，可再封存）
 * OVER_SHORT 三方不一致转长短款挂账
 * OVER_SHORT_SETTLED 长短款已在 3 个自然日内销账
 * ESCORT_ABNORMAL 押运超时异常（冻结该 ATM 后续计划）
 * SEALED 日结封存（终态；迟到数据仅登记迟到事件）
 */
public enum PlanStatus {
    PREPARED,
    APPROVED,
    IN_TRANSIT,
    ESCORT_DONE,
    COUNTED,
    DEVICE_CONFIRMED,
    RECONCILED,
    OVER_SHORT,
    OVER_SHORT_SETTLED,
    ESCORT_ABNORMAL,
    SEALED
}
