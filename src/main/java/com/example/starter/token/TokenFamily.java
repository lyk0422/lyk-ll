package com.example.starter.token;

import java.time.Instant;

/**
 * 令牌族（一次会话）。数据库中仅保存族级状态，不落任何令牌明文。
 *
 * @param id           族 ID（合成 UUID）
 * @param userId       合成用户标识
 * @param status       族状态
 * @param createdAt    创建时间（UTC）
 * @param revokedAt    撤销时间（UTC），未撤销时为 null
 * @param revokeReason 撤销原因（LOGOUT / REPLAY_DETECTED），未撤销时为 null
 */
public record TokenFamily(
        String id,
        String userId,
        FamilyStatus status,
        Instant createdAt,
        Instant revokedAt,
        String revokeReason) {
}
