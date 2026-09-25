package com.example.starter.cosmetic.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 备案处理结果响应（提交/变更/异议/注销）。
 *
 * @param httpStatus       等价 HTTP 状态码（200/409 等），便于幂等快照复算
 * @param filing           备案详情
 * @param idempotentReplay 是否为同键同参重放（返回首次结果）
 * @param reviewDetails    本次形式/异议审查明细
 * @param atUtc            处理时刻(UTC)
 */
public record FilingResultResponse(
        int httpStatus,
        FilingResponse filing,
        boolean idempotentReplay,
        List<ReviewDetailResponse> reviewDetails,
        Instant atUtc) {
}
