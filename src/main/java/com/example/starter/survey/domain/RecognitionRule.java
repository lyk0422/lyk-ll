package com.example.starter.survey.domain;

import java.time.Instant;

/**
 * 合成互认规则版本（创建后不可变）。
 *
 * @param id               主键
 * @param requestId        创建幂等键
 * @param ruleVersion      规则版本号（全局唯一，从 1 开始）
 * @param flagCode         适用船旗代码（合成）
 * @param maxEvidenceDays  证据最长有效天数（申请互认时刻须不晚于完成时刻+该天数，恰到截止允许）
 * @param fingerprint      业务指纹（requestId + 全部业务字段）
 * @param createdAt        创建时刻（UTC）
 */
public record RecognitionRule(
        Long id,
        String requestId,
        int ruleVersion,
        String flagCode,
        int maxEvidenceDays,
        String fingerprint,
        Instant createdAt
) {
}
