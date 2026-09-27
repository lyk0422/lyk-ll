package com.example.starter.cosmetic.child.domain;

import com.example.starter.cosmetic.domain.FormulationIngredient;

import java.time.Instant;
import java.util.List;

/**
 * 儿童备案修订（持久化对象的内存镜像，字段口径与 child_filing_revision 表一致）。
 *
 * <p>受理（进入公示）时冻结：分档安全报告清单、分龄规则版本、标识文本摘要。
 * 所有时刻字段均为 UTC。</p>
 */
public record ChildRevisionRecord(
        Long id,
        String filingNo,
        int revisionNo,
        ChildRevisionType revisionType,
        ChildRevisionStatus status,
        int ageLowerMonths,
        int ageUpperMonths,
        List<String> claims,
        List<FormulationIngredient> formulation,
        List<ChildSafetyReport> safetyReports,
        List<ChildLabel> labels,
        String ruleVersion,
        String labelDigest,
        String packagingText,
        String rejectReason,
        String requestId,
        Instant submittedAtUtc,
        Instant acceptedAtUtc,
        Instant publicExpiresAtUtc,
        Instant effectiveAtUtc) {
}
