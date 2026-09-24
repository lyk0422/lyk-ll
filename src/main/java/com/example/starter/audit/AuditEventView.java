package com.example.starter.audit;

import java.time.Instant;

/**
 * 审计事件视图。id 递增顺序即事件发生顺序。
 */
public record AuditEventView(
        long id,
        String screeningNo,
        String eventType,
        String actor,
        String detail,
        Instant occurredAt) {
}
