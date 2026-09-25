package com.example.starter.ili.dto;

import java.math.BigDecimal;

/**
 * 匹配对视图：对齐成功的旧新缺陷及其分级与复核结论。
 *
 * @param pairId               匹配对主键
 * @param oldDefectRef         上一回合缺陷编号
 * @param newDefectRef         本回合缺陷编号
 * @param distanceM            里程差绝对值，单位米
 * @param growthRatePctPerYear 深度增长率，单位 %/年
 * @param grade                分级：STABLE/GROWING/SEVERE
 * @param reviewStatus         复核状态：PENDING/CONFIRMED/DOWNGRADED
 * @param finalGrade           复核后最终分级；null 表示尚未复核
 */
public record PairView(
        long pairId,
        String oldDefectRef,
        String newDefectRef,
        BigDecimal distanceM,
        BigDecimal growthRatePctPerYear,
        String grade,
        String reviewStatus,
        String finalGrade) {
}
