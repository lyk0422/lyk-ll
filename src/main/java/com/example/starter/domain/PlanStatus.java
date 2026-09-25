package com.example.starter.domain;

/**
 * 方案状态：SUBMITTED→APPROVED/REJECTED→EXECUTING→STOPPED/BANNED→SEALED。
 */
public enum PlanStatus {
    /** 已申报，待审核。 */
    SUBMITTED,
    /** 审核通过，可启动执行。 */
    APPROVED,
    /** 整单拒绝（替代率超限、氯硫加权超限、许可过期等）。 */
    REJECTED,
    /** 执行中，占用窑。 */
    EXECUTING,
    /** 已停用（手动停止或切换时旧方案落态）。 */
    STOPPED,
    /** 连续3小时超标导致当日禁烧。 */
    BANNED,
    /** 日结封存，终态。 */
    SEALED
}
