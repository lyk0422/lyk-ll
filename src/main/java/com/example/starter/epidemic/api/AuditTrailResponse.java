package com.example.starter.epidemic.api;

import com.example.starter.epidemic.domain.AuditAction;

import java.time.Instant;

/**
 * 审核轨迹条目。
 */
public record AuditTrailResponse(
        int versionNo,
        AuditAction action,
        String operator,
        String detail,
        Instant createdAt
) {
}
