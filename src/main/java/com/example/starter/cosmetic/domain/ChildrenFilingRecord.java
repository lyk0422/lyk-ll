package com.example.starter.cosmetic.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 儿童备案记录（持久化对象的内存镜像，字段口径与 children_filing 表一致）。
 *
 * <p>所有时刻字段均为 UTC；日期字段为 UTC 日界。每次材料补正生成一行新修订，
 * 受理时冻结报告清单、分龄规则版本及标识文本摘要。</p>
 *
 * @param id                主键
 * @param filingNo          儿童备案编号
 * @param productCode       产品编码
 * @param productName       产品名称
 * @param versionNo         版本号，从1开始，成功修改（补正/变更）加1
 * @param revisionNo        修订号，材料补正生成新修订
 * @param parentFilingNo    实质变更来源备案编号
 * @param changeType        变更类型：INITIAL/SUBSTANTIVE/SIMPLE
 * @param status            状态
 * @param ageLowerMonths    申请年龄下限（整数月，闭区间，0~144）
 * @param ageUpperMonths    申请年龄上限（整数月，闭区间，0~144）
 * @param formulation       配方快照
 * @param claims            儿童功效宣称
 * @param safetyReports     受理冻结的儿童安全报告清单
 * @param labels            受理冻结的适用年龄标识
 * @param labelDigest       受理冻结的标识文本摘要(SHA-256)
 * @param ruleVersion       受理冻结的分龄规则版本
 * @param packagingText     包装文字内容
 * @param requestId         幂等键
 * @param submittedAtUtc    本修订提交时刻(UTC)
 * @param publicNoticeDateUtc 公示起算日(UTC 日界)
 * @param publicExpiresAtUtc  公示期满时刻(UTC)
 * @param effectiveAtUtc    生效时刻(UTC)
 */
public record ChildrenFilingRecord(
        Long id,
        String filingNo,
        String productCode,
        String productName,
        int versionNo,
        int revisionNo,
        String parentFilingNo,
        ChangeType changeType,
        ChildrenFilingStatus status,
        int ageLowerMonths,
        int ageUpperMonths,
        List<FormulationIngredient> formulation,
        List<String> claims,
        List<ChildrenSafetyReport> safetyReports,
        List<AgeLabel> labels,
        String labelDigest,
        int ruleVersion,
        String packagingText,
        String requestId,
        Instant submittedAtUtc,
        LocalDate publicNoticeDateUtc,
        Instant publicExpiresAtUtc,
        Instant effectiveAtUtc
) {
}
