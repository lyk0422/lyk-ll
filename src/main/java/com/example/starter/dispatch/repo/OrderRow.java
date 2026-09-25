package com.example.starter.dispatch.repo;

import java.time.Instant;

/**
 * dispatch_order 表行记录。时间字段统一为 UTC 毫秒时间戳。
 */
public record OrderRow(
        String requestId,
        String fingerprint,
        String status,
        long deadline,
        long issuedAt,
        Long acknowledgedAt,
        Long finishedAt,
        boolean escalated,
        boolean takenOver,
        String abortNote,
        String payloadJson,
        long createdAt) {

    public Instant deadlineInstant() {
        return Instant.ofEpochMilli(deadline);
    }
}
