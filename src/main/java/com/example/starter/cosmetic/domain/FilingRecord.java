package com.example.starter.cosmetic.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 备案记录（持久化对象的内存镜像，字段口径与 filing_record 表一致）。
 *
 * <p>所有时刻字段均为 UTC；日期字段为 UTC 日界。</p>
 *
 * <p>儿童备案扩展字段（filingCategory=CHILD 时非空）：申请年龄整数月闭区间、
 * 受理冻结的分龄规则版本、儿童安全报告清单快照、适用年龄标识文本快照及其摘要。</p>
 */
public record FilingRecord(
        Long id,
        String filingNo,
        String productCode,
        String productName,
        int versionNo,
        String parentFilingNo,
        ChangeType changeType,
        FilingStatus status,
        List<FormulationIngredient> formulation,
        List<String> claims,
        List<TestReportRecord> reports,
        String packagingText,
        String requestId,
        Instant submittedAtUtc,
        Instant formReviewedAtUtc,
        LocalDate publicNoticeDateUtc,
        Instant publicExpiresAtUtc,
        Instant objectionReceivedAtUtc,
        Instant effectiveAtUtc,
        Instant withdrawnAtUtc,
        String rejectReason,
        FilingCategory filingCategory,
        Integer ageLowerMonths,
        Integer ageUpperMonths,
        String ageRuleVersion,
        List<ChildBandDossier> childDossiers,
        String labelDigest
) {
    /** 是否儿童备案。 */
    public boolean isChild() {
        return filingCategory == FilingCategory.CHILD;
    }
}
