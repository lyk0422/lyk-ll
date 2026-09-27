package com.example.starter.metro.dto.refund;

import java.util.List;

import com.example.starter.metro.dto.ReplayableResponse;

/**
 * 退款确认成功响应：退款单占用行程可退余额并登记独立调账单。
 *
 * @param refundNo        退款单号
 * @param adjustmentNo    生成的调账单号
 * @param txnNo           原交易流水号
 * @param amountCents     退款金额（分），与各线路负向明细合计绝对值相等
 * @param status          退款单状态（CONFIRMED）
 * @param version         退款单版本，确认成功后为 2
 * @param lineAdjustments 按原线路实收份额分摊的负向调整明细
 * @param replayed        是否幂等重放命中首次结果
 */
public record RefundConfirmResponse(String refundNo, String adjustmentNo, String txnNo,
                                    long amountCents, String status, int version,
                                    List<LineAdjustment> lineAdjustments, boolean replayed)
        implements ReplayableResponse<RefundConfirmResponse> {

    @Override
    public RefundConfirmResponse asReplayed() {
        return new RefundConfirmResponse(refundNo, adjustmentNo, txnNo, amountCents, status,
                version, lineAdjustments, true);
    }

    /**
     * 单条线路负向调整。
     *
     * @param lineCode      原承运线路编码
     * @param amountCents   分摊退款（分，正数）
     */
    public record LineAdjustment(String lineCode, long amountCents) {
    }
}
