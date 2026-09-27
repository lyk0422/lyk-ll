package com.example.starter.cosmetic.child.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 儿童备案写操作结果响应（提交/补正/变更）。
 *
 * @param httpStatus       等价 HTTP 状态码，便于幂等快照复算
 * @param filing           备案修订详情
 * @param idempotentReplay 是否为同键同参重放（返回首次冻结结果）
 * @param reviewDetails    本次分龄形式审查明细
 * @param atUtc            受理时刻(UTC)
 */
public record ChildFilingResultResponse(
        int httpStatus,
        ChildFilingResponse filing,
        boolean idempotentReplay,
        List<ChildReviewDetailResponse> reviewDetails,
        Instant atUtc) {
}
