package com.example.starter.trial.repository;

import java.time.Instant;

/**
 * unblinding_audit_event 审计事件。id 自增，即发生顺序。
 */
public record AuditEventRow(
        long id,
        String trialId,
        String subjectScreenNo,
        String requestId,
        String eventType,
        String actorUserId,
        String detail,
        Instant createdAtUtc) {
}
