package com.example.starter.cosmetic.web.dto;

import java.time.Instant;

/**
 * 标签领用结果响应。
 *
 * @param httpStatus       等价 HTTP 状态码，便于幂等快照复算
 * @param issue            本次领用凭据（可追溯）
 * @param printRun         领用后印次台账详情（余额与版本）
 * @param idempotentReplay 是否为同键同参重放（返回首次结果）
 * @param atUtc            处理时刻(UTC)
 */
public record LabelIssueResultResponse(
        int httpStatus,
        LabelIssueResponse issue,
        PrintRunResponse printRun,
        boolean idempotentReplay,
        Instant atUtc) {

    /** 构造同键同参重放响应（返回首次结果快照）。 */
    public LabelIssueResultResponse asReplay() {
        return new LabelIssueResultResponse(httpStatus, issue, printRun, true, atUtc);
    }
}
