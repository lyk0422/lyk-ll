package com.example.starter.metro.dto.refund;

import java.util.List;

/**
 * 行程逐线路可退余额查询响应。
 *
 * @param txnNo          原交易流水号
 * @param originalCents  原实收金额（分）
 * @param refundedCents  已确认退款累计（分）
 * @param refundableCents 剩余可退余额（分）
 * @param lines          逐线路原份额、已退与可退余额
 */
public record RefundBalanceResponse(String txnNo, long originalCents, long refundedCents,
                                    long refundableCents, List<LineBalance> lines) {

    /**
     * 单条线路退款余额。
     *
     * @param lineCode        原承运线路编码
     * @param originalCents   原实收份额（分）
     * @param refundedCents   该线路累计已退（分）
     * @param refundableCents 该线路剩余可退余额（分）
     */
    public record LineBalance(String lineCode, long originalCents, long refundedCents,
                              long refundableCents) {
    }
}
