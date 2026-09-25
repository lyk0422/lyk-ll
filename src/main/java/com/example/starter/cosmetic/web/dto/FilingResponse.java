package com.example.starter.cosmetic.web.dto;

import com.example.starter.cosmetic.domain.FilingRecord;
import com.example.starter.cosmetic.domain.FormulationIngredient;
import com.example.starter.cosmetic.domain.TestReportRecord;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 备案详情响应。时间均为 UTC，日期为 UTC 日界。
 */
public record FilingResponse(
        String filingNo,
        String productCode,
        String productName,
        int versionNo,
        String parentFilingNo,
        String changeType,
        String status,
        List<FormulationIngredient> formulation,
        List<String> claims,
        List<TestReportView> reports,
        String packagingText,
        String requestId,
        Instant submittedAtUtc,
        Instant formReviewedAtUtc,
        LocalDate publicNoticeDateUtc,
        Instant publicExpiresAtUtc,
        Instant objectionReceivedAtUtc,
        Instant effectiveAtUtc,
        Instant withdrawnAtUtc,
        String rejectReason) {

    /** 报告视图。 */
    public record TestReportView(String reportNo,
                                 String efficacyCode,
                                 LocalDate issuedDate,
                                 boolean conclusionSupported,
                                 String conclusionText) {

        static TestReportView from(TestReportRecord r) {
            return new TestReportView(r.reportNo(), r.efficacyCode(), r.issuedDate(),
                    r.conclusionSupported(), r.conclusionText());
        }
    }

    /** 由领域记录构造响应。 */
    public static FilingResponse from(FilingRecord r) {
        List<TestReportView> reports = r.reports() == null ? List.of()
                : r.reports().stream().map(TestReportView::from).toList();
        return new FilingResponse(
                r.filingNo(), r.productCode(), r.productName(), r.versionNo(),
                r.parentFilingNo(), r.changeType().name(), r.status().name(),
                r.formulation(), r.claims(), reports, r.packagingText(), r.requestId(),
                r.submittedAtUtc(), r.formReviewedAtUtc(), r.publicNoticeDateUtc(),
                r.publicExpiresAtUtc(), r.objectionReceivedAtUtc(), r.effectiveAtUtc(),
                r.withdrawnAtUtc(), r.rejectReason());
    }
}
