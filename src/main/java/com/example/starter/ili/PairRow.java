package com.example.starter.ili;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * ili_match_pair 表行：按里程容差对齐的旧新读数及其分级与复核结论。
 *
 * @param id                   匹配对主键
 * @param batchId              所属批次主键
 * @param oldReadingId         上一回合读数主键，全表唯一
 * @param newReadingId         本回合读数主键，全表唯一
 * @param distanceM            两读数里程差绝对值，单位米，不超过 0.5 米（含）
 * @param growthRatePctPerYear 深度增长率，单位 %/年；负值表示深度减小
 * @param grade                分级：STABLE/GROWING/SEVERE
 * @param reviewStatus         复核状态：PENDING/CONFIRMED/DOWNGRADED
 * @param finalGrade           复核后最终分级；null 表示尚未复核
 * @param reviewer             复核人标识；null 表示尚未复核
 * @param reviewComment        复核备注；null 表示无备注
 * @param reviewedAt           复核时间（UTC）；null 表示尚未复核
 */
public record PairRow(
        long id,
        long batchId,
        long oldReadingId,
        long newReadingId,
        BigDecimal distanceM,
        BigDecimal growthRatePctPerYear,
        Grade grade,
        ReviewStatus reviewStatus,
        Grade finalGrade,
        String reviewer,
        String reviewComment,
        Instant reviewedAt) {
}
