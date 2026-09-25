package com.example.starter.ventilation.repo;

import java.time.LocalDateTime;

/**
 * idem_request 表行记录：保存首次响应以便同键重放。
 */
public record IdemRow(
        String requestId,
        String requestKind,
        String fingerprint,
        Integer httpStatus,
        String responseJson,
        LocalDateTime createdAt) {
}
