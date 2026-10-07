package com.example.starter.cosmetic.web.dto;

import java.time.Instant;

/**
 * 未领标签销毁登记结果响应。
 *
 * @param httpStatus       等价 HTTP 状态码，便于幂等快照复算
 * @param destruction      本次销毁登记（留证）
 * @param printRun         销毁后印次台账详情（余额与版本）
 * @param idempotentReplay 是否为同键同参重放（返回首次结果）
 * @param atUtc            处理时刻(UTC)
 */
public record LabelDestructionResultResponse(
        int httpStatus,
        LabelDestructionResponse destruction,
        PrintRunResponse printRun,
        boolean idempotentReplay,
        Instant atUtc) {

    /** 构造同键同参重放响应（返回首次结果快照）。 */
    public LabelDestructionResultResponse asReplay() {
        return new LabelDestructionResultResponse(httpStatus, destruction, printRun, true, atUtc);
    }
}
