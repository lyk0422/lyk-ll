package com.example.starter.survey.domain;

import java.time.LocalDateTime;

/**
 * 幂等请求行：保存首次响应快照（HTTP 状态码 + JSON）。
 */
public record IdempotentRow(
        String requestId,
        String operation,
        String fingerprint,
        int httpStatus,
        String resultJson,
        LocalDateTime createdAt) {
}
