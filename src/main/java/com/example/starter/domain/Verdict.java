package com.example.starter.domain;

/**
 * 出缸判定结论。
 */
public enum Verdict {
    /** 合格：缸差不超过 0.5。 */
    PASS,
    /** 回修：缸差超过 0.5 且尚未回修过，只允许回修一次。 */
    REPAIR,
    /** 降级处置：回修后复测仍超差。 */
    DOWNGRADE
}
