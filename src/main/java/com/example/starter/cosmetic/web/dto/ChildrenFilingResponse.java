package com.example.starter.cosmetic.web.dto;

import com.example.starter.cosmetic.domain.AgeLabel;
import com.example.starter.cosmetic.domain.ChildrenFilingRecord;
import com.example.starter.cosmetic.domain.ChildrenSafetyReport;
import com.example.starter.cosmetic.domain.FormulationIngredient;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 儿童备案详情响应。时间均为 UTC，日期为 UTC 日界。
 */
public record ChildrenFilingResponse(
        String filingNo,
        String productCode,
        String productName,
        int versionNo,
        int revisionNo,
        String parentFilingNo,
        String changeType,
        String status,
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
        Instant effectiveAtUtc) {

    /** 由领域记录构造响应。 */
    public static ChildrenFilingResponse from(ChildrenFilingRecord r) {
        return new ChildrenFilingResponse(
                r.filingNo(), r.productCode(), r.productName(), r.versionNo(), r.revisionNo(),
                r.parentFilingNo(), r.changeType().name(), r.status().name(),
                r.ageLowerMonths(), r.ageUpperMonths(),
                r.formulation(), r.claims(), r.safetyReports(), r.labels(),
                r.labelDigest(), r.ruleVersion(), r.packagingText(), r.requestId(),
                r.submittedAtUtc(), r.publicNoticeDateUtc(), r.publicExpiresAtUtc(),
                r.effectiveAtUtc());
    }
}
