package com.example.starter.domain;

/**
 * 加钞计划状态机。
 * 流转：DRAFT 预测编制 → APPROVED 已审批 → ESCORTED 双人押运完成 → COUNTED 双人清点完成
 * → DEVICE_CONFIRMED 设备确认 → RECONCILED 三方一致核销 / DISCREPANCY 长短款待销账 → SEALED 日结封存（终态）。
 * 押运超时则进入 ESCORT_ABNORMAL 并冻结该 ATM 后续计划。
 * 临时计划与在途计划冲突裁决中金额较小一方进入 YIELDED 让路（保留排队位置，可恢复执行）。
 */
public enum PlanStatus {
    DRAFT,
    APPROVED,
    ESCORTED,
    COUNTED,
    DEVICE_CONFIRMED,
    RECONCILED,
    DISCREPANCY,
    ESCORT_ABNORMAL,
    YIELDED,
    SEALED
}
