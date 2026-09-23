package com.example.starter.api;

import java.time.Instant;

/**
 * 令牌族状态视图。
 *
 * @param familyId         族标识
 * @param userId           合成用户标识
 * @param clientId         客户端标识
 * @param status           族状态（ACTIVE/REVOKED）
 * @param revokeReason     撤销原因（REPLAY_DETECTED/LOGOUT）；未撤销为 {@code null}
 * @param createdAt        创建时间（UTC）
 * @param revokedAt        撤销时间（UTC）；未撤销为 {@code null}
 * @param tokenCount       族内令牌总数
 * @param activeTokenCount 仍为 ACTIVE 状态的令牌数
 */
public record FamilyStatusResponse(
        String familyId,
        String userId,
        String clientId,
        String status,
        String revokeReason,
        Instant createdAt,
        Instant revokedAt,
        int tokenCount,
        int activeTokenCount
) {
}
