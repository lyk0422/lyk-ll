package com.example.starter.domain;

import java.time.Instant;

/**
 * 令牌族持久化对象。一个族代表一次会话的完整令牌链。
 *
 * @param id            自增主键
 * @param familyId      对外暴露的族标识（合成随机串）
 * @param userId        合成用户标识
 * @param clientId      客户端标识
 * @param status        族状态
 * @param createdAt     创建时间（epoch 毫秒，UTC）
 * @param updatedAt     最近更新时间（epoch 毫秒，UTC）
 * @param revokedAt     撤销时间（epoch 毫秒，UTC）；{@code null} 表示未撤销
 * @param revokeReason  撤销原因（REPLAY_DETECTED / LOGOUT）；{@code null} 表示未撤销
 */
public record TokenFamily(
        Long id,
        String familyId,
        String userId,
        String clientId,
        FamilyStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant revokedAt,
        String revokeReason
) {
}
