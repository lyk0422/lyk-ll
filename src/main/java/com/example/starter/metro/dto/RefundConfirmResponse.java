package com.example.starter.metro.dto;

import java.util.List;

/**
 * 退款双人确认响应：确认成功即占用行程可退余额并登记独立负向调整单。
 *
 * @param refundNo     退款单号
 * @param status       退款单状态（CONFIRMED，终态）
 * @param adjustNo     调整单号
 * @param batchId      纳入的调账批次 ID
 * @param amountCents  本次退款金额（分，正数）
 * @param allocations  按原线路实收份额分摊的负向明细
 * @param version      退款单版本（确认成功后为 2）
 * @param replayed     是否幂等重放命中首次结果
 */
public record RefundConfirmResponse(String refundNo, String status, String adjustNo, long batchId,
                                    long amountCents, List<LineAllocation> allocations, int version,
                                    boolean replayed)
        implements ReplayableResponse<RefundConfirmResponse> {

    @Override
    public RefundConfirmResponse asReplayed() {
        return new RefundConfirmResponse(refundNo, status, adjustNo, batchId, amountCents,
                allocations, version, true);
    }

    /**
     * 单线路调整分摊。
     *
     * @param lineCode    线路编码
     * @param amountCents 负向调整金额（分）
     */
    public record LineAllocation(String lineCode, long amountCents) {
    }
}
