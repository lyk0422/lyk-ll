package com.example.starter.dye.domain;

/**
 * 缸差判定处置结论。
 */
public enum Disposition {
    /** 合格 */
    PASS,
    /** 回修（仅允许一次） */
    REPAIR,
    /** 降级处置（回修后仍超差） */
    GRADED_DOWN
}
