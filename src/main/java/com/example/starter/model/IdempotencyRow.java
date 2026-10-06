package com.example.starter.model;

/**
 * 幂等记录行：responseBody 为首次成功响应快照。
 */
public record IdempotencyRow(
        String requestId,
        String endpoint,
        String fingerprint,
        String responseBody) {
}
