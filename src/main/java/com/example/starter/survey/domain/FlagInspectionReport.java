package com.example.starter.survey.domain;

import java.time.Instant;

/**
 * 外部船旗国检查证据报告（合成签认；报告编号全局唯一，更正生成新修订）。
 *
 * @param id               主键
 * @param requestId        导入/更正幂等键
 * @param reportNo         报告编号（全局唯一，跨修订不变）
 * @param revision         修订号（从 1 开始，更正加 1）
 * @param imo              本船 IMO
 * @param flagCode         船旗代码
 * @param completedAt      外部检查完成时刻（UTC）
 * @param syntheticSignoff 合成签认标记（仅接受 SYNTHETIC-SIGNOFF）
 * @param createdAt        创建时刻（UTC）
 */
public record FlagInspectionReport(
        Long id,
        String requestId,
        String reportNo,
        int revision,
        String imo,
        String flagCode,
        Instant completedAt,
        String syntheticSignoff,
        Instant createdAt
) {
}
