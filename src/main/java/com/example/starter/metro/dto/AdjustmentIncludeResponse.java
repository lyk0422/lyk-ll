package com.example.starter.metro.dto;

/**
 * 调整单纳入批次响应。
 *
 * @param adjustmentNo    调整单号
 * @param batchNo         调账批次号
 * @param batchStatus     批次状态（OPEN）
 * @param version         纳入后批次版本（每次成功纳入加 1；重复纳入版本不变）
 * @param alreadyIncluded 是否重复纳入（幂等命中，未重复计额）
 * @param replayed        是否幂等重放命中首次冻结结果
 */
public record AdjustmentIncludeResponse(String adjustmentNo, String batchNo, String batchStatus,
                                        int version, boolean alreadyIncluded, boolean replayed)
        implements ReplayableResponse<AdjustmentIncludeResponse> {

    @Override
    public AdjustmentIncludeResponse asReplayed() {
        return new AdjustmentIncludeResponse(adjustmentNo, batchNo, batchStatus, version,
                alreadyIncluded, true);
    }
}
