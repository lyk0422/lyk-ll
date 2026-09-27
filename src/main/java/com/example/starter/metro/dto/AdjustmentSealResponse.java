package com.example.starter.metro.dto;

/**
 * 调账批次封存响应（终态）。
 *
 * @param batchNo         调账批次号
 * @param status          批次状态（SEALED）
 * @param totalCents      批次内负向调整明细合计（分，负数或 0），封存时快照
 * @param adjustmentCount 已纳入调整单数量
 * @param version         封存后版本
 * @param replayed        是否幂等重放命中首次结果
 */
public record AdjustmentSealResponse(String batchNo, String status, long totalCents,
                                     int adjustmentCount, int version, boolean replayed)
        implements ReplayableResponse<AdjustmentSealResponse> {

    @Override
    public AdjustmentSealResponse asReplayed() {
        return new AdjustmentSealResponse(batchNo, status, totalCents, adjustmentCount, version, true);
    }
}
