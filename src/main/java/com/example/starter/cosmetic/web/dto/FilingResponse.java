package com.example.starter.cosmetic.web.dto;

import com.example.starter.cosmetic.domain.ChildBandDossier;
import com.example.starter.cosmetic.domain.FilingRecord;
import com.example.starter.cosmetic.domain.FormulationIngredient;
import com.example.starter.cosmetic.domain.TestReportRecord;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 备案详情响应。时间均为 UTC，日期为 UTC 日界。
 *
 * <p>儿童备案（filingCategory=CHILD）附带受理冻结的分龄审查证据：
 * 申请年龄区间、分龄规则版本、分龄档材料清单与标识文本摘要。</p>
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
        String rejectReason,
        String filingCategory,
        Integer ageLowerMonths,
        Integer ageUpperMonths,
        String ageRuleVersion,
        List<ChildDossierView> childDossiers,
        String labelDigest) {

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

    /** 儿童分龄档材料视图（受理冻结）。 */
    public record ChildDossierView(String bandCode,
                                   String reportNo,
                                   int coverageLowerMonths,
                                   int coverageUpperMonths,
                                   boolean conclusionSupported,
                                   String conclusionText,
                                   String labelText) {

        static ChildDossierView from(ChildBandDossier d) {
            return new ChildDossierView(d.bandCode(), d.report().reportNo(),
                    d.report().coverageLowerMonths(), d.report().coverageUpperMonths(),
                    d.report().conclusionSupported(), d.report().conclusionText(), d.labelText());
        }
    }

    /** 由领域记录构造响应。 */
    public static FilingResponse from(FilingRecord r) {
        List<TestReportView> reports = r.reports() == null ? List.of()
                : r.reports().stream().map(TestReportView::from).toList();
        List<ChildDossierView> dossiers = r.childDossiers() == null ? null
                : r.childDossiers().stream().map(ChildDossierView::from).toList();
        return new FilingResponse(
                r.filingNo(), r.productCode(), r.productName(), r.versionNo(),
                r.parentFilingNo(), r.changeType().name(), r.status().name(),
                r.formulation(), r.claims(), reports, r.packagingText(), r.requestId(),
                r.submittedAtUtc(), r.formReviewedAtUtc(), r.publicNoticeDateUtc(),
                r.publicExpiresAtUtc(), r.objectionReceivedAtUtc(), r.effectiveAtUtc(),
                r.withdrawnAtUtc(), r.rejectReason(),
                r.filingCategory().name(), r.ageLowerMonths(), r.ageUpperMonths(),
                r.ageRuleVersion(), dossiers, r.labelDigest());
    }
}
