package com.example.starter.token;

import java.time.Instant;

/**
 * 安全事件持久化对象。每个 (familyId, eventType) 唯一，保证终态事件只记录一次。
 *
 * @param id        自增主键
 * @param eventType 事件类型
 * @param familyId  相关令牌族 ID
 * @param tokenHash 触达事件的令牌散列（可能为 null）
 * @param requestId 触达事件的请求键（可能为 null）
 * @param detail    补充说明
 * @param createdAt 事件时间（UTC）
 */
public record SecurityEvent(
        Long id,
        SecurityEventType eventType,
        String familyId,
        String tokenHash,
        String requestId,
        String detail,
        Instant createdAt) {
}
