package com.example.starter.survey.domain;

import java.time.Instant;

/**
 * 合成互认规则版本（船旗代码 + 项目映射 + 最长证据有效天数）。
 *
 * @param id                 主键
 * @param version            规则版本号，从 1 开始，全局唯一
 * @param flagCode           船旗代码（合成，不对应真实机构）
 * @param maxEvidenceAgeDays 证据最长有效天数（UTC；恰到截止允许）
 * @param createdAt          创建时刻（UTC）
 */
public record FlagRule(
        Long id,
        Integer version,
        String flagCode,
        Integer maxEvidenceAgeDays,
        Instant createdAt
) {
}
