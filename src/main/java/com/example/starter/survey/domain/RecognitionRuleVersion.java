package com.example.starter.survey.domain;

import java.time.Instant;

/**
 * 船旗国检查互认规则版本（合成规则，不用于生产）。
 *
 * @param id                    主键
 * @param version               版本号，从 1 开始，成功发布新版本 +1
 * @param maxEvidenceValidDays  外部证据最长有效天数（自然日；UTC 瞬时比较）
 * @param status                ACTIVE 可引用 / FROZEN 停用
 * @param note                  版本说明
 * @param createdAt             创建时刻（UTC）
 */
public record RecognitionRuleVersion(
        Long id,
        int version,
        int maxEvidenceValidDays,
        RecognitionRuleStatus status,
        String note,
        Instant createdAt
) {
}
