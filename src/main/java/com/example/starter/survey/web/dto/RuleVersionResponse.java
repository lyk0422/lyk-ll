package com.example.starter.survey.web.dto;

import com.example.starter.survey.domain.RecognitionRuleVersion;

import java.util.List;

/** 规则版本视图。 */
public record RuleVersionResponse(
        int version,
        int maxEvidenceValidDays,
        String status,
        String note,
        String createdAt,
        List<String> flagCodes,
        List<RuleMappingView> mappings
) {

    /** 规则映射视图。 */
    public record RuleMappingView(String flagItemCode, String surveyType, String classItemCode) {
    }

    /** 组装版本视图（含船旗与映射明细）。 */
    public static RuleVersionResponse of(RecognitionRuleVersion rule,
                                         List<String> flagCodes,
                                         List<RuleMappingView> mappings) {
        return new RuleVersionResponse(rule.version(), rule.maxEvidenceValidDays(),
                rule.status().name(), rule.note(),
                rule.createdAt() == null ? null : rule.createdAt().toString(),
                flagCodes, mappings);
    }
}
