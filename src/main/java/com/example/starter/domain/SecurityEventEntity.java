package com.example.starter.domain;

import java.time.Instant;

/**
 * 安全事件持久化对象。每个族至多有一条 REPLAY_DETECTED 事件（数据库唯一约束保证）。
 *
 * @param id                 自增主键
 * @param familyId           所属令牌族标识
 * @param eventType          事件类型
 * @param requestId          触发事件的请求键；可能为 {@code null}
 * @param tokenHash          触发事件的令牌 SHA-256
 * @param requestFingerprint 请求指纹（族标识+请求键+客户端+令牌哈希的 SHA-256）
 * @param detail             事件补充说明
 * @param createdAt          事件时间（epoch 毫秒，UTC）
 */
public record SecurityEventEntity(
        Long id,
        String familyId,
        SecurityEventType eventType,
        String requestId,
        String tokenHash,
        String requestFingerprint,
        String detail,
        Instant createdAt
) {
}
