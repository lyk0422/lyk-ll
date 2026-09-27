package com.example.starter.metro.dto;

import java.util.List;

/**
 * 行程逐线路退款余额查询响应。
 *
 * @param txnNo              原交易流水号
 * @param originalTotalCents 原实收合计（分，封账快照口径，永不改写）
 * @param refundedCents      累计已退（分）
 * @param remainingCents     尚可退余额（分），恰退满为 0
 * @param lines              逐线路原份额、已退与余额
 */
public record RefundBalanceResponse(String txnNo, long originalTotalCents, long refundedCents,
                                    long remainingCents, List<LineBalance> lines) {

    /**
     * 单线路退款余额。
     *
     * @param lineCode        线路编码
     * @param originalCents   原实收份额（分）
     * @param refundedCents   该线路累计已退（分，绝对值口径）
     * @param remainingCents  该线路尚可退余额（分）
     */
    public record LineBalance(String lineCode, long originalCents, long refundedCents,
                              long remainingCents) {
    }
}
