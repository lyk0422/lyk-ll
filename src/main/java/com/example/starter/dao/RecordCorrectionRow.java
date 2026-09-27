package com.example.starter.dao;

import java.time.Instant;

/**
 * 历史水下记录更正差异行：独立留痕，不追改已关闭潜次的原记录、台账与积分。
 */
public record RecordCorrectionRow(
        Long id,
        String diveRequestId,
        long targetRecordId,
        int planVersion,
        String diverCode,
        String phase,
        int recordedDurationMin,
        int correctedDurationMin,
        int deltaMin,
        String reason,
        int version,
        Instant correctedAtUtc,
        Instant createdAtUtc) {
}
