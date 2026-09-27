package com.example.starter.cosmetic.child.web.dto;

import com.example.starter.cosmetic.child.domain.ChildFilingRecord;
import com.example.starter.cosmetic.child.domain.ChildSafetyReport;
import com.example.starter.cosmetic.domain.FormulationIngredient;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 儿童备案修订详情响应。时间均为 UTC，日期为 UTC 日界，年龄为整数月闭区间。
 */
public record ChildFilingResponse(
        String filingNo,
        String productCode,
        String productName,
        int versionNo,
        String parentFilingNo,
        String changeType,
        String status,
        int ageFromMonths,
        int ageToMonths,
        List<String> requiredTiers,
        List<FormulationIngredient> formulation,
        List<String> claims,
        List<SafetyReportView> safetyReports,
        String ageLabelText,
        String ageLabelDigest,
        String ageRuleVersion,
        String packagingText,
        String requestId,
        Instant submittedAtUtc,
        LocalDate publicNoticeDateUtc,
        Instant publicExpiresAtUtc,
        Instant effectiveAtUtc,
        Instant voidedAtUtc,
        String rejectReason) {

    /** 儿童安全报告视图。 */
    public record SafetyReportView(String reportNo,
                                   String tierCode,
                                   LocalDate issuedDate,
                                   int coversFromMonths,
                                   int coversToMonths,
                                   boolean conclusionSupported,
                                   String conclusionText) {

        static SafetyReportView from(ChildSafetyReport r) {
            return new SafetyReportView(r.reportNo(), r.tier().name(), r.issuedDate(),
                    r.coversFromMonths(), r.coversToMonths(),
                    r.conclusionSupported(), r.conclusionText());
        }
    }

    /** 由领域记录构造响应。 */
    public static ChildFilingResponse from(ChildFilingRecord r) {
        List<SafetyReportView> reports = r.safetyReports() == null ? List.of()
                : r.safetyReports().stream().map(SafetyReportView::from).toList();
        return new ChildFilingResponse(
                r.filingNo(), r.productCode(), r.productName(), r.versionNo(),
                r.parentFilingNo(), r.changeType().name(), r.status().name(),
                r.ageFromMonths(), r.ageToMonths(),
                r.requiredTiers().stream().map(Enum::name).toList(),
                r.formulation(), r.claims(), reports,
                r.ageLabelText(), r.ageLabelDigest(), r.ageRuleVersion(),
                r.packagingText(), r.requestId(), r.submittedAtUtc(),
                r.publicNoticeDateUtc(), r.publicExpiresAtUtc(), r.effectiveAtUtc(),
                r.voidedAtUtc(), r.rejectReason());
    }
}
