package com.example.starter.rolling.domain;

/**
 * 排产单状态：编制 DRAFT → 校验 VALIDATED → 下达 ISSUED → 执行 EXECUTING → 冻结 FROZEN。
 */
public enum ScheduleStatus {
    DRAFT,
    VALIDATED,
    ISSUED,
    EXECUTING,
    FROZEN
}
