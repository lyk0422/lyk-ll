package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 批次视图。
 *
 * @param lotNo           批次编号
 * @param varietyCode     品种编码
 * @param plotCode        地块编码
 * @param declaredGrade   申报等级 ORIGINAL/IMPROVED
 * @param labelQuantity   申报标签数量（张）
 * @param availableLabels 当前可用标签数量（张）
 * @param status          PENDING/CERTIFIED/CANCELLED
 * @param declaredAt      申报时间（UTC）
 * @param revokedAt       注销时间（UTC），空值表示未注销
 * @param revokeReason    注销原因，空值表示未注销
 */
public record LotView(
        String lotNo,
        String varietyCode,
        String plotCode,
        String declaredGrade,
        int labelQuantity,
        int availableLabels,
        String status,
        Instant declaredAt,
        Instant revokedAt,
        String revokeReason
) {
}
