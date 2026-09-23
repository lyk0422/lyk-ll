package com.example.starter.domain;

import java.time.Instant;

/**
 * 刷新令牌持久化对象。数据库只保存令牌明文的 SHA-256、父子关系、状态与到期时间，
 * 绝不保存明文。
 *
 * @param id           自增主键
 * @param familyId     所属令牌族标识
 * @param tokenHash    令牌明文的 SHA-256（十六进制）
 * @param parentHash   父令牌 SHA-256；族内首个令牌为 {@code null}
 * @param sequenceNo   族内序号，首个令牌为 0，每轮换一次加 1
 * @param status       令牌状态
 * @param clientId     签发时绑定的客户端标识
 * @param expiresAt    到期时间（epoch 毫秒，UTC）；now &gt;= 该值即失效
 * @param consumedAt   消费时间（epoch 毫秒，UTC）；{@code null} 表示未消费
 * @param createdAt    创建时间（epoch 毫秒，UTC）
 */
public record RefreshTokenEntity(
        Long id,
        String familyId,
        String tokenHash,
        String parentHash,
        int sequenceNo,
        TokenStatus status,
        String clientId,
        Instant expiresAt,
        Instant consumedAt,
        Instant createdAt
) {
}
