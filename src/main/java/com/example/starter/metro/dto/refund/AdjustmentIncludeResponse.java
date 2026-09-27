package com.example.starter.metro.dto.refund;

import com.example.starter.metro.dto.ReplayableResponse;

/**
 * 调账单纳入开放批次响应。
 *
 * @param adjustmentNo 调账单号
 * @param batchNo      纳入的调账批次号
 * @param status       调账单状态（INCLUDED）
 * @param version      调账单版本，纳入成功后为 2
 * @param replayed     是否幂等重放命中首次结果
 */
public record AdjustmentIncludeResponse(String adjustmentNo, String batchNo, String status,
                                        int version, boolean replayed)
        implements ReplayableResponse<AdjustmentIncludeResponse> {

    @Override
    public AdjustmentIncludeResponse asReplayed() {
        return new AdjustmentIncludeResponse(adjustmentNo, batchNo, status, version, true);
    }
}
