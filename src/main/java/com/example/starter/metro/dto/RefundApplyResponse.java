package com.example.starter.metro.dto;

/**
 * 退款申请响应。
 *
 * @param refundNo 退款单号
 * @param txnNo    原交易流水号
 * @param amountCents 本次退款金额（分）
 * @param status   退款单状态（PENDING）
 * @param version  退款单版本（新建为 1）
 * @param replayed 是否幂等重放命中首次结果
 */
public record RefundApplyResponse(String refundNo, String txnNo, long amountCents, String status,
                                  int version, boolean replayed)
        implements ReplayableResponse<RefundApplyResponse> {

    @Override
    public RefundApplyResponse asReplayed() {
        return new RefundApplyResponse(refundNo, txnNo, amountCents, status, version, true);
    }
}
