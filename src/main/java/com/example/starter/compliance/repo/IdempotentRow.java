package com.example.starter.compliance.repo;

/**
 * requestId 幂等记录行；fingerprint 覆盖 requestId 与全部业务字段。
 */
public record IdempotentRow(
        String requestId,
        String fingerprint,
        int statusCode,
        String responseJson,
        long createdAt) {
}
