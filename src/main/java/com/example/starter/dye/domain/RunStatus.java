package com.example.starter.dye.domain;

/**
 * 缸次生命周期状态。
 */
public enum RunStatus {
    /** 已排缸，尚未投料 */
    PLANNED,
    /** 投料执行中 */
    RUNNING,
    /** 已出缸，等待缸差判定（回修复检后也回到该状态） */
    DISCHARGED,
    /** 已判定回修，等待复检 */
    REPAIRED,
    /** 已降级处置，等待归档 */
    GRADED_DOWN,
    /** 判定归档（终态） */
    ARCHIVED
}
