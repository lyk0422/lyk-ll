package com.example.starter.cosmetic.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 儿童备案处理结果响应（提交/补正/变更）。
 *
 * @param httpStatus       等价 HTTP 状态码（200/409 等），便于幂等快照复算
 * @param filing           儿童备案详情
 * @param idempotentReplay 是否为同键同参重放（返回首次冻结结果）
 * @param reviewDetails    本次审查明细
 * @param atUtc            处理时刻(UTC)
 */
public record ChildrenFilingResultResponse(
        int httpStatus,
        ChildrenFilingResponse filing,
        boolean idempotentReplay,
        List<ReviewDetailResponse> reviewDetails,
        Instant atUtc) {
}
