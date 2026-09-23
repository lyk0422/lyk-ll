package com.example.starter.domain;

import java.time.Instant;

/**
 * 轮换请求记录，用于安全重试的幂等恢复与请求指纹冲突检测。
 *
 * @param id                  自增主键
 * @param familyId            所属令牌族标识
 * @param requestId           请求键（客户端生成，族内唯一）
 * @param clientId            请求携带的客户端标识
 * @param presentedTokenHash  请求携带的当前令牌 SHA-256
 * @param requestFingerprint  请求指纹（族标识+请求键+客户端+令牌哈希的 SHA-256）
 * @param childTokenHash      本次轮换产生的子令牌 SHA-256
 * @param createdAt           记录时间（epoch 毫秒，UTC）
 */
public record RotationRequestEntity(
        Long id,
        String familyId,
        String requestId,
        String clientId,
        String presentedTokenHash,
        String requestFingerprint,
        String childTokenHash,
        Instant createdAt
) {
}
