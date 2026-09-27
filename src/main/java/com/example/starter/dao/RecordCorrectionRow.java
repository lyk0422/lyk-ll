package com.example.starter.dao;

import java.time.Instant;

/**
 * 历史水下记录更正行：不追改已关闭潜次，另记独立差异（以操作 requestId 为键）。
 *
 * @param requestId            本次更正操作幂等键
 * @param diveRequestId        被更正的已关闭（旧语义）潜次
 * @param diverCode            被更正记录的潜水员
 * @param phase                被更正阶段
 * @param recordedDurationMin  原记录历时，分钟
 * @param correctedDurationMin 更正后历时，分钟
 * @param deltaMin             独立差异（更正-原记录），分钟；不回写已关闭潜次与积分账
 */
public record RecordCorrectionRow(
        String requestId,
        String diveRequestId,
        String diverCode,
        String phase,
        int recordedDurationMin,
        int correctedDurationMin,
        int deltaMin,
        String fingerprint,
        Instant createdAtUtc) {
}
