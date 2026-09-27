package com.example.starter.metro.dto.refund;

import com.example.starter.metro.dto.ReplayableResponse;

/**
 * 调账批次封存响应。
 *
 * @param batchNo         调账批次号
 * @param status          批次状态（SEALED）
 * @param totalCents      封存时快照的调账合计（分，负数或 0）
 * @param adjustmentCount 纳入调账单笔数
 * @param version         批次版本，封存成功后为 2
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
