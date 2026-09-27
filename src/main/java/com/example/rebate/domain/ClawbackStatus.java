package com.example.rebate.domain;

/**
 * 冲回单状态。
 *
 * <p>{@code PENDING} 待复核（不占额）；{@code APPROVED} 已批准并占用剩余可冲额；
 * {@code REJECTED} 已驳回（不占额）；{@code COMPLETED} 追缴已收齐（终态只读）。</p>
 */
public enum ClawbackStatus {
    /** 待复核，尚未占用可冲额度。 */
    PENDING,
    /** 已批准，同一事务内占用额度并产生追缴明细。 */
    APPROVED,
    /** 已驳回，不占额。 */
    REJECTED,
    /** 追缴累计恰收齐，终态只读。 */
    COMPLETED
}
