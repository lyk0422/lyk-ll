package com.example.starter.specimen.model;

import java.time.Instant;

/**
 * 幂等记录行：requestId 唯一；fingerprint 识别同键异参；responseBody 为首次响应快照。
 */
public record IdempotencyRecordRow(
        String requestId,
        String operation,
        String fingerprint,
        String responseBody,
        Instant createdAt) {
}
