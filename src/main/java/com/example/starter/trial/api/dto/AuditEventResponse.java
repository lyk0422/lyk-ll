package com.example.starter.trial.api.dto;

import java.time.Instant;

/**
 * 揭盲审计事件，按发生顺序（id 递增）返回。
 */
public record AuditEventResponse(
        long id,
        String trialCode,
        String screeningNo,
        String eventType,
        Instant eventTimeUtc,
        String requestId,
        String operator,
        String detail) {
}
