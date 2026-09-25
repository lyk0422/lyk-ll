package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 种子批次持久化行。
 *
 * @param id              主键
 * @param lotNo           批次号
 * @param applicant       申报主体
 * @param varietyName     品种名称（合成数据）
 * @param fieldCode       地块编号（合成数据）
 * @param areaMu          申报面积，单位：亩
 * @param availableLabels 当前可用标签数量，单位：枚；发证时同事务核减
 * @param status          批次状态
 * @param determinedGrade 已确定的合格分级；空表示尚无合格分级
 * @param recheckCount    已受理复检次数（每批至多 1）
 * @param unqualifiedAt   首检不合格结论出具时间（UTC），复检 15 日窗口起算点；空表示尚未首检不合格
 * @param createdAt       创建时间（UTC）
 * @param updatedAt       更新时间（UTC）
 */
public record SeedLot(
        Long id,
        String lotNo,
        String applicant,
        String varietyName,
        String fieldCode,
        BigDecimal areaMu,
        int availableLabels,
        LotStatus status,
        Grade determinedGrade,
        int recheckCount,
        Instant unqualifiedAt,
        Instant createdAt,
        Instant updatedAt) {
}
