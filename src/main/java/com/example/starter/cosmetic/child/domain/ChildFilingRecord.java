package com.example.starter.cosmetic.child.domain;

import com.example.starter.cosmetic.domain.FormulationIngredient;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 儿童备案修订记录（持久化对象的内存镜像，字段口径与 child_filing 表一致）。
 *
 * <p>每次首次提交/补正/变更均产生一个修订（versionNo 递增）；同 filingNo 多个修订，
 * 补正使旧待审修订失效，公示与生效只针对最新修订。所有时刻字段为 UTC；日期为 UTC 日界；
 * 年龄为整数月闭区间，与时区无关。</p>
 */
public record ChildFilingRecord(
        Long id,
        String filingNo,
        String productCode,
        String productName,
        int versionNo,
        String parentFilingNo,
        ChildChangeType changeType,
        ChildFilingStatus status,
        int ageFromMonths,
        int ageToMonths,
        List<AgeTier> requiredTiers,
        List<FormulationIngredient> formulation,
        List<String> claims,
        List<ChildSafetyReport> safetyReports,
        String ageLabelText,
        String ageLabelDigest,
        String ageRuleVersion,
        String packagingText,
        String requestId,
        Instant submittedAtUtc,
        Instant formReviewedAtUtc,
        LocalDate publicNoticeDateUtc,
        Instant publicExpiresAtUtc,
        Instant effectiveAtUtc,
        Instant voidedAtUtc,
        String rejectReason
) {
}
