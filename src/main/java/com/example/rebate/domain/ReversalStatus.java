package com.example.rebate.domain;

/**
 * 分批冲回单状态。
 *
 * <p>PENDING 待复核 → APPROVED 已批准（追缴中）/ REJECTED 已驳回（不占额）；
 * 追缴回执累计恰收齐批准金额后 APPROVED → COMPLETED（终态，只读）。</p>
 */
public enum ReversalStatus {

    /** 待复核：已提交未占用可冲额。 */
    PENDING,

    /** 已批准：同事务占用剩余可冲额并产生追缴明细，回执登记中。 */
    APPROVED,

    /** 已驳回：不占用任何可冲额，终态。 */
    REJECTED,

    /** 追缴完成：回执累计恰等于批准金额，只读，终态。 */
    COMPLETED
}
