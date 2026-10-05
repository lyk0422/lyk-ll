package com.example.starter.feed.dto;

import java.time.Instant;

/**
 * 出栏申请撤销记录视图（审计可查）。
 *
 * @param requestId                 被撤销出栏申请的原 requestId
 * @param batchNo                   养殖批次号
 * @param revokedSlaughterAtUtc     被撤销的原计划出栏时刻（UTC）
 * @param newEarliestSlaughterUtc   重算后的新最早合法出栏时刻（UTC）
 * @param treatmentReportId         触发撤销的治疗直报主键
 * @param reason                    撤销原因
 * @param createdAtUtc              撤销记录创建时刻（UTC）
 */
public record SlaughterRevocationResponse(
        String requestId,
        String batchNo,
        Instant revokedSlaughterAtUtc,
        Instant newEarliestSlaughterUtc,
        long treatmentReportId,
        String reason,
        Instant createdAtUtc
) {
}
