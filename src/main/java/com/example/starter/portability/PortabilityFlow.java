package com.example.starter.portability;

import java.time.Instant;

/**
 * 携号转网流程实体，对应 portability_flow 表。
 * authCode/authIssuedAt/authExpiresAt 为空表示尚未签发授权码；
 * 时间字段均为 UTC 口径。
 */
public record PortabilityFlow(
        Long id,
        String requestId,
        String msisdn,
        String donorOperator,
        String recipientOperator,
        FlowState state,
        String authCode,
        Instant authIssuedAt,
        Instant authExpiresAt,
        Instant createdAt,
        Instant updatedAt) {
}
