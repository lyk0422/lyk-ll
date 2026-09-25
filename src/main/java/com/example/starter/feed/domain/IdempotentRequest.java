package com.example.starter.feed.domain;

import java.time.Instant;

/**
 * 幂等请求台账行。
 *
 * @param requestId    幂等键
 * @param operation    操作类型
 * @param fingerprint  请求指纹
 * @param responseJson 首次响应快照
 * @param createdAt    首次请求时刻（UTC）
 */
public record IdempotentRequest(
        String requestId,
        String operation,
        String fingerprint,
        String responseJson,
        Instant createdAt) {
}
