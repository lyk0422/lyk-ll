package com.example.starter.domain;

import java.time.Instant;

/**
 * 种子批次（申报记录）。
 *
 * @param lotNo           批次业务编号
 * @param varietyCode     品种编码
 * @param plotCode        地块编码
 * @param declaredGrade   申报等级
 * @param labelQuantity   申报标签数量（张）
 * @param availableLabels 当前可用标签数量（张），发证同事务核减
 * @param status          批次状态
 * @param declaredAt      申报时间（UTC）
 * @param revokedAt       注销时间（UTC），空值表示未注销
 * @param revokeReason    注销原因，空值表示未注销
 * @param updatedAt       最近更新时间（UTC）
 */
public record SeedLot(
        String lotNo,
        String varietyCode,
        String plotCode,
        SeedGrade declaredGrade,
        int labelQuantity,
        int availableLabels,
        LotStatus status,
        Instant declaredAt,
        Instant revokedAt,
        String revokeReason,
        Instant updatedAt
) {
}
