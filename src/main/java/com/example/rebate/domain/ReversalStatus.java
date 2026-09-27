package com.example.rebate.domain;

/**
 * 冲回单状态。
 *
 * <p>{@code PENDING} 待复核，不占用可冲额；{@code APPROVED} 复核已批准并在同事务占用剩余可冲额，
 * 追缴进行中；{@code REJECTED} 已驳回（不占额，终态）；{@code COMPLETED} 追缴收齐（只读终态）。</p>
 */
public enum ReversalStatus {
    /** 待复核：已提交，等待不同于提交人的复核人处理。 */
    PENDING,
    /** 已批准：已占用可冲额，等待追缴回执登记。 */
    APPROVED,
    /** 已驳回：不占额，不可再处理。 */
    REJECTED,
    /** 追缴完成：实际收回累计恰等于批准额，只读。 */
    COMPLETED
}
