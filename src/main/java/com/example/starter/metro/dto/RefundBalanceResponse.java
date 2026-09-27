package com.example.starter.metro.dto;

import java.util.List;

/**
 * 行程逐线路退款余额查询响应。
 *
 * @param txnNo            原交易流水号
 * @param originalFareCents 原实收金额（分）
 * @param refundedCents     累计已退金额（分）
 * @param availableCents    行程可退余额（分）= 原实收 - 累计已退
 * @param lines             逐线路原份额、已退占用与可退余额
 */
public record RefundBalanceResponse(String txnNo, long originalFareCents, long refundedCents,
                                    long availableCents, List<LineBalance> lines) {

    /** 逐线路余额视图，金额单位均为分。 */
    public record LineBalance(String lineCode, long originalShareCents, long refundedCents,
                              long availableCents) {
    }
}
