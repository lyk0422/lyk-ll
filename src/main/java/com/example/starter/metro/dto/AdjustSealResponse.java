package com.example.starter.metro.dto;

import java.util.List;

/**
 * 调账批次封存响应。
 *
 * @param batchId     批次 ID
 * @param status      批次状态（SEALED，终态）
 * @param totalCents  批次合计快照（分，负数）
 * @param orderCount  批次内调整单笔数
 * @param version     批次版本（封存成功加 1）
 * @param details     批次内全部调整单及其线路明细（封存证据）
 * @param replayed    是否幂等重放命中首次结果
 */
public record AdjustSealResponse(long batchId, String status, long totalCents, int orderCount,
                                 int version, List<AdjustOrderView> details, boolean replayed)
        implements ReplayableResponse<AdjustSealResponse> {

    @Override
    public AdjustSealResponse asReplayed() {
        return new AdjustSealResponse(batchId, status, totalCents, orderCount, version, details, true);
    }

    /**
     * 调整单视图。
     *
     * @param adjustNo    调整单号
     * @param refundNo    来源退款单号
     * @param txnNo       原交易流水号
     * @param amountCents 调整合计（分，负数）
     * @param lines       线路级负向明细
     */
    public record AdjustOrderView(String adjustNo, String refundNo, String txnNo, long amountCents,
                                  List<RefundConfirmResponse.LineAllocation> lines) {
    }
}
