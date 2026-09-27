package com.example.starter.survey.domain;

import java.time.Instant;

/**
 * 外部船旗国检查报告头（报告编号全局唯一；更正只追加新修订）。
 *
 * @param id              主键
 * @param reportNo        报告编号（全局唯一）
 * @param shipImo         本船 IMO
 * @param flagCode        船旗代码（合成）
 * @param currentRevision 当前修订号，从 1 开始，更正成功加 1
 * @param createdAt       创建时刻（UTC）
 */
public record FlagReport(
        Long id,
        String reportNo,
        String shipImo,
        String flagCode,
        Integer currentRevision,
        Instant createdAt
) {
}
