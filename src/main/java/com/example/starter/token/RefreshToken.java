package com.example.starter.token;

import java.time.Instant;

/**
 * 刷新令牌持久化对象；仅保存 SHA-256 散列与父子关系，不保存明文。
 *
 * @param tokenHash   明文令牌的 SHA-256 十六进制散列
 * @param familyId    所属令牌族 ID
 * @param parentHash  父令牌散列；族内首个令牌为 null
 * @param seq         族内代数，根令牌为 0，每轮换一次加一
 * @param status      令牌状态
 * @param clientId    创建/轮换该令牌时的合成客户端标识
 * @param issuedAt    签发时间（UTC）
 * @param expiresAt   到期时间（UTC）；now &gt;= expiresAt 即失效
 * @param consumedAt  被轮换消费时间（UTC），未消费时为 null
 */
public record RefreshToken(
        String tokenHash,
        String familyId,
        String parentHash,
        int seq,
        TokenStatus status,
        String clientId,
        Instant issuedAt,
        Instant expiresAt,
        Instant consumedAt) {
}
