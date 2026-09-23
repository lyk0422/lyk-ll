package com.example.starter.token.api;

import java.time.Instant;

/**
 * 安全事件视图。
 *
 * @param id        事件 ID
 * @param eventType 事件类型（REPLAY_DETECTED / LOGOUT）
 * @param familyId  相关令牌族 ID
 * @param requestId 触达事件的请求键，可能为 null
 * @param detail    补充说明
 * @param createdAt 事件时间（UTC）
 */
public record SecurityEventResponse(
        Long id,
        String eventType,
        String familyId,
        String requestId,
        String detail,
        Instant createdAt) {
}
