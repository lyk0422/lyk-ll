package com.example.starter.fishery.domain;

/**
 * 触发人工复核的原因。
 */
public enum ReviewReason {
    /** 兼捕保护鱼种占比超过 5%。 */
    BYCATCH_RATIO,
    /** 进港卸渔量与日志累计量偏差超过 3%。 */
    LANDING_DEVIATION,
    /** 结案后迟到日志。 */
    LATE_LOG
}
