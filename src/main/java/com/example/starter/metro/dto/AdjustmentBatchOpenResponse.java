package com.example.starter.metro.dto;

/**
 * 调账批次开放响应。
 *
 * @param batchNo  调账批次号
 * @param status   批次状态（OPEN）
 * @param version  批次版本（新建为 1）
 * @param replayed 是否幂等重放命中首次结果
 */
public record AdjustmentBatchOpenResponse(String batchNo, String status, int version, boolean replayed)
        implements ReplayableResponse<AdjustmentBatchOpenResponse> {

    @Override
    public AdjustmentBatchOpenResponse asReplayed() {
        return new AdjustmentBatchOpenResponse(batchNo, status, version, true);
    }
}
