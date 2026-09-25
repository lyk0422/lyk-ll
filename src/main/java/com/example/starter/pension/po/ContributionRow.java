package com.example.starter.pension.po;

import com.example.starter.pension.domain.ContributionType;

import java.time.LocalDate;

/**
 * contribution 表行对象。
 *
 * @param id             主键
 * @param memberId       成员主键
 * @param type           缴费类型
 * @param amountCents    缴费金额，单位：分
 * @param contributionDate 缴费归属日期（UTC 日历日）
 * @param period         缴费所属期
 * @param snapshotPlanId 冻结该缴费的计划 ID；null 表示未冻结
 */
public record ContributionRow(
        long id,
        long memberId,
        ContributionType type,
        long amountCents,
        LocalDate contributionDate,
        String period,
        Long snapshotPlanId) {
}
