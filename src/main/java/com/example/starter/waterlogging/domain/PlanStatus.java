package com.example.starter.waterlogging.domain;

/**
 * 预降单状态机常量：DRAINING（预降中）→ COMPLETED（序列完成）→ ARCHIVED（复盘归档，终态）。
 */
public final class PlanStatus {

    /** 预降进行中，分区序列锁持有中。 */
    public static final String DRAINING = "DRAINING";
    /** 全部泵站已停机、目标位达成，分区序列锁已释放。 */
    public static final String COMPLETED = "COMPLETED";
    /** 复盘归档终态。 */
    public static final String ARCHIVED = "ARCHIVED";

    private PlanStatus() {
    }
}
