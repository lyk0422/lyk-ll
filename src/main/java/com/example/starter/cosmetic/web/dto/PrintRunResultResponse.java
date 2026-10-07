package com.example.starter.cosmetic.web.dto;

import java.time.Instant;

/**
 * 印次登记结果响应。
 *
 * @param httpStatus       等价 HTTP 状态码，便于幂等快照复算
 * @param printRun         印次台账详情
 * @param idempotentReplay 是否为同键同参重放（返回首次结果）
 * @param atUtc            处理时刻(UTC)
 */
public record PrintRunResultResponse(
        int httpStatus,
        PrintRunResponse printRun,
        boolean idempotentReplay,
        Instant atUtc) {
}
