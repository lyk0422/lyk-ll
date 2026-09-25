package com.example.starter.cutover.domain;

/**
 * 回退判据命中类型，写入 rollback_record.trigger_reason，可复算。
 */
public enum RollbackReason {

    /** 关键验证点超时（自步骤开始起算，恰满 10 分钟未通过即命中）。 */
    VERIFY_TIMEOUT,
    /** 关键验证点硬性检查失败。 */
    HARD_CHECK_FAILED,
    /** 普通步骤执行失败。 */
    STEP_FAILED,
    /** 窗口结束仍有未完成步骤，转窗口超期并强制回退。 */
    WINDOW_OVERDUE,
    /** 人工/系统强制回退。 */
    FORCED
}
