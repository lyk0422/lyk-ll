package com.example.starter.survey.domain;

import java.time.Instant;

/**
 * 外部船旗国检查报告（证据）的一个修订。
 *
 * @param id                 主键
 * @param requestId          导入/更正幂等键
 * @param reportNo           报告编号（全局唯一；同一编号的所有修订属于同一报告）
 * @param shipImo            本船 IMO
 * @param flagCode           船旗代码（合成）
 * @param completedAt        检查完成时刻（UTC）
 * @param attestation        签认标记（仅接受合成标记）
 * @param revision           修订号：首次导入为 1，每次更正 +1
 * @param version            实体版本：从 1 开始，每次成功更正 +1
 * @param status             ACTIVE 当前有效 / SUPERSEDED 被替代
 * @param supersedesId       被本修订替代的上一修订主键，首修订为 null
 * @param contentFingerprint 报告业务内容指纹（不含 requestId），用于同编号异内容冲突判定
 * @param fingerprint        业务指纹（requestId + 全部业务字段）
 * @param createdAt          创建时刻（UTC）
 */
public record InspectionReport(
        Long id,
        String requestId,
        String reportNo,
        String shipImo,
        String flagCode,
        Instant completedAt,
        String attestation,
        int revision,
        int version,
        ReportStatus status,
        Long supersedesId,
        String contentFingerprint,
        String fingerprint,
        Instant createdAt
) {
}
