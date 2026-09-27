package com.example.starter.metro.dto;

import java.util.List;

/**
 * 退款复核响应：首人复核后 status=IN_REVIEW；最终（第二名不同复核人）确认后
 * status=CONFIRMED 并返回调整单号与逐线路分摊。
 *
 * @param refundNo      退款单号
 * @param adjustmentNo  调整单号（仅最终确认后非空）
 * @param txnNo         原交易流水号
 * @param amountCents   本次退款金额（分）
 * @param status        退款单状态：IN_REVIEW / CONFIRMED
 * @param version       退款单当前版本（首人复核后 2，最终确认后 3）
 * @param firstReviewer 第一名复核人
 * @param finalReviewer 最终复核人（仅最终确认后非空）
 * @param confirmed     是否完成双人确认
 * @param allocations   逐线路退款分摊（金额为正，仅最终确认后非空）
 * @param replayed      是否幂等重放命中首次冻结结果
 */
public record RefundConfirmResponse(String refundNo, String adjustmentNo, String txnNo,
                                    long amountCents, String status, int version,
                                    String firstReviewer, String finalReviewer, boolean confirmed,
                                    List<LineAllocation> allocations, boolean replayed)
        implements ReplayableResponse<RefundConfirmResponse> {

    /** 逐线路退款分摊视图（金额为正，合计等于退款金额）。 */
    public record LineAllocation(String lineCode, long refundCents) {
    }

    @Override
    public RefundConfirmResponse asReplayed() {
        return new RefundConfirmResponse(refundNo, adjustmentNo, txnNo, amountCents, status, version,
                firstReviewer, finalReviewer, confirmed, allocations, true);
    }
}
