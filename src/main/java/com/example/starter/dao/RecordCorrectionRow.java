package com.example.starter.dao;

import java.time.Instant;

/**
 * 历史水下记录更正差异行：不追改已关闭潜次，独立记录差异。
 */
public record RecordCorrectionRow(
        Long id,
        String diveRequestId,
        String diverCode,
        int oldBottomMin,
        int newBottomMin,
        int deltaMin,
        String requestId,
        Instant correctedAtUtc,
        Instant createdAtUtc) {
}
