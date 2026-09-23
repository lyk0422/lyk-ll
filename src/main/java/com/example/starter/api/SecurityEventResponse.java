package com.example.starter.api;

import java.time.Instant;

/**
 * 安全事件视图。
 *
 * @param id                 事件标识
 * @param familyId           所属令牌族
 * @param eventType          事件类型（当前为 REPLAY_DETECTED）
 * @param requestId          触发事件的请求键
 * @param requestFingerprint 请求指纹
 * @param detail             事件补充说明
 * @param createdAt          事件时间（UTC）
 */
public record SecurityEventResponse(
        Long id,
        String familyId,
        String eventType,
        String requestId,
        String requestFingerprint,
        String detail,
        Instant createdAt
) {
}
