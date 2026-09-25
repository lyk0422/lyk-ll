package com.example.starter.web;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 批次视图（申报、查询、流转结果统一返回）。
 *
 * @param lotNo            批次号
 * @param applicant        申报主体
 * @param varietyName      品种名称
 * @param fieldCode        地块编号
 * @param areaMu           申报面积（亩）
 * @param availableLabels  当前可用标签数量（枚）
 * @param status           批次状态
 * @param determinedGrade  已确定分级；空表示尚无合格分级
 * @param recheckCount     已受理复检次数
 * @param unqualifiedAt    首检不合格时间（UTC）；空表示无
 * @param fieldInspections 田间检验记录（含首检与复检，首检记录保留可查）
 * @param labInspections   室内检测记录（含首检与复检，首检记录保留可查）
 * @param certificate      已发证书视图；空表示未发证
 * @param createdAt        创建时间（UTC）
 * @param updatedAt        更新时间（UTC）
 */
public record LotView(
        String lotNo,
        String applicant,
        String varietyName,
        String fieldCode,
        BigDecimal areaMu,
        int availableLabels,
        String status,
        String determinedGrade,
        int recheckCount,
        Instant unqualifiedAt,
        java.util.List<InspectionView> fieldInspections,
        java.util.List<InspectionView> labInspections,
        CertView certificate,
        Instant createdAt,
        Instant updatedAt) {
}
