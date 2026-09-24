package com.example.starter.portability.model;

import java.time.Instant;

/**
 * 携号转网流程记录，对应 portability_flow 表。
 * 时间字段均为 UTC、毫秒精度；authCode* 在未签码时为 null。
 */
public record FlowRecord(
        long id,
        String requestId,
        String msisdn,
        FlowStatus status,
        String authCode,
        Instant authCodeIssuedAt,
        Instant authCodeExpiresAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
