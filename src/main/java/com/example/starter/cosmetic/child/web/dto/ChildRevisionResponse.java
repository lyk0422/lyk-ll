package com.example.starter.cosmetic.child.web.dto;

import com.example.starter.cosmetic.child.domain.ChildLabel;
import com.example.starter.cosmetic.child.domain.ChildRevisionRecord;
import com.example.starter.cosmetic.child.domain.ChildSafetyReport;
import com.example.starter.cosmetic.domain.FormulationIngredient;

import java.time.Instant;
import java.util.List;

/**
 * 儿童备案修订响应（含受理冻结的报告清单、分龄规则版本与标识文本摘要）。
 */
public record ChildRevisionResponse(
        int revisionNo,
        String revisionType,
        String status,
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
        Instant submittedAtUtc,
        Instant acceptedAtUtc,
        Instant publicExpiresAtUtc,
        Instant effectiveAtUtc) {

    /** 由领域记录构造响应。 */
    public static ChildRevisionResponse from(ChildRevisionRecord r) {
        return new ChildRevisionResponse(
                r.revisionNo(), r.revisionType().name(), r.status().name(),
                r.ageLowerMonths(), r.ageUpperMonths(),
                r.claims(), r.formulation(), r.safetyReports(), r.labels(),
                r.ruleVersion(), r.labelDigest(), r.packagingText(), r.rejectReason(),
                r.submittedAtUtc(), r.acceptedAtUtc(), r.publicExpiresAtUtc(), r.effectiveAtUtc());
    }
}
