package com.example.starter.schedule;

/**
 * 排产单状态：编制 → 校验 → 下达 → 执行 → 冻结（终态）。
 */
public enum ScheduleStatus {
    /** 编制中，可修改，未校验。 */
    DRAFT,
    /** 校验通过，可下达。 */
    VALIDATED,
    /** 已下达，版本冻结，修改须生成新版本。 */
    RELEASED,
    /** 执行中，已有实绩回传。 */
    EXECUTING,
    /** 日终冻结，终态；迟到实绩只记事件不改结论。 */
    FROZEN
}
