package com.example.starter.cosmetic.child.web.dto;

import com.example.starter.cosmetic.web.dto.ReviewDetailResponse;

import java.time.Instant;
import java.util.List;

/**
 * 儿童备案写操作结果响应（提交/补正/变更）。
 *
 * @param httpStatus       等价 HTTP 状态码（200），便于幂等快照复算
 * @param filing           儿童备案详情（含当前最新修订）
 * @param idempotentReplay 是否为同键同参重放（返回首次冻结结果）
 * @param evidence         本次审查判定证据（缺项一次列全，可复算）
 * @param atUtc            处理时刻(UTC)
 */
public record ChildFilingResultResponse(
        int httpStatus,
        ChildFilingResponse filing,
        boolean idempotentReplay,
        List<ReviewDetailResponse> evidence,
        Instant atUtc) {
}
