package com.example.starter.rebate.repo;

/**
 * idempotent_request 行。
 */
public record IdempotentRow(
        String requestId,
        String bizType,
        String fingerprint,
        int statusCode,
        String responseJson
) {
}
