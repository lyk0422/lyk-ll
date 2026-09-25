package com.example.starter.trial.api;

import java.time.Instant;

/**
 * 揭盲审计事件（按发生顺序返回）。
 *
 * @param seq             顺序号（自增，越大越晚发生）
 * @param trialId         试验编号
 * @param subjectScreenNo 受试者筛选号
 * @param requestId       关联揭盲申请编号
 * @param eventType       事件类型（UNBLINDING_REQUESTED / FIRST_CONFIRMED / SECOND_CONFIRMED / UNBLINDED / REPLAYED 等）
 * @param actorUserId     操作人工号
 * @param detail          事件说明
 * @param occurredAtUtc   事件发生时刻（UTC）
 */
public record AuditEventResponse(
        long seq,
        String trialId,
        String subjectScreenNo,
        String requestId,
        String eventType,
        String actorUserId,
        String detail,
        Instant occurredAtUtc) {
}
