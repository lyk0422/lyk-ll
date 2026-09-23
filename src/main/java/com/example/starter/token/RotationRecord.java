package com.example.starter.token;

import java.time.Instant;

/**
 * 轮换请求指纹；以 requestId 为唯一键，支撑安全重试与请求冲突检测。
 *
 * @param requestId  请求键
 * @param familyId   令牌族 ID
 * @param parentHash 被消费的父令牌散列
 * @param clientId   请求的客户端标识
 * @param childHash  首次成功轮换派生出的子令牌散列
 * @param createdAt  记录时间（UTC）
 */
public record RotationRecord(
        String requestId,
        String familyId,
        String parentHash,
        String clientId,
        String childHash,
        Instant createdAt) {
}
