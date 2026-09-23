package com.example.starter.token.api;

import java.time.Instant;

/**
 * 令牌族状态视图。
 *
 * @param familyId     族 ID
 * @param userId       合成用户标识
 * @param status       族状态（ACTIVE / REVOKED）
 * @param createdAt    创建时间（UTC）
 * @param revokedAt    撤销时间（UTC），未撤销为 null
 * @param revokeReason 撤销原因，未撤销为 null
 * @param tokenCount   族内令牌数量（含各代）
 */
public record FamilyStatusResponse(
        String familyId,
        String userId,
        String status,
        Instant createdAt,
        Instant revokedAt,
        String revokeReason,
        int tokenCount) {
}
