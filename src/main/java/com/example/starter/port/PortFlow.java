package com.example.starter.port;

import java.time.Instant;

/**
 * 携号转网流程记录（port_request 表行映射）。时间字段均为 UTC。
 *
 * @param authCode 授权码；生效后作废置 null
 * @param authCodeIssuedAt 授权码签发时刻；未签发为 null
 * @param authCodeExpiresAt 授权码失效时刻（恰满 24 小时即失效）；未签发为 null
 * @param effectiveAt 生效时刻；未生效为 null
 * @param cancelledAt 撤销时刻；未撤销为 null
 * @param cancelReason 撤销原因；未撤销为 null
 */
public record PortFlow(
        long id,
        String requestId,
        String fingerprint,
        String msisdn,
        String donorOperator,
        String recipientOperator,
        PortStatus status,
        String authCode,
        Instant authCodeIssuedAt,
        Instant authCodeExpiresAt,
        Instant effectiveAt,
        Instant cancelledAt,
        String cancelReason,
        Instant createdAt,
        Instant updatedAt) {
}
