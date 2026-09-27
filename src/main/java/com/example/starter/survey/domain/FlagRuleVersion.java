package com.example.starter.survey.domain;

import java.time.Instant;

/**
 * 船旗国互认规则版本（不可变；每船旗从 1 开始，成功变更加 1）。
 *
 * @param id                 主键
 * @param requestId          变更幂等键
 * @param flagCode           船旗代码（合成数据，不对应真实机构）
 * @param version            规则版本号（从 1 开始）
 * @param maxEvidenceAgeDays 证据最长有效天数（完成时刻+该天数为截止，恰到截止允许）
 * @param createdAt          创建时刻（UTC）
 */
public record FlagRuleVersion(
        Long id,
        String requestId,
        String flagCode,
        int version,
        int maxEvidenceAgeDays,
        Instant createdAt
) {
}
