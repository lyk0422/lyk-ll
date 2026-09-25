package com.example.starter.repo;

// idempotent_request 行记录：保存首次响应快照与指纹。
public record IdempotentRow(
        String requestId,
        String requestKind,
        String fingerprint,
        int httpStatus,
        String responseJson,
        long createdAt) {
}
