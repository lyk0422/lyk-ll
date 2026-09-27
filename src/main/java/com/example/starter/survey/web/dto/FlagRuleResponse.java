package com.example.starter.survey.web.dto;

import com.example.starter.survey.domain.FlagRule;
import com.example.starter.survey.domain.FlagRuleItem;

import java.util.List;

/** 互认规则版本视图（含项目映射）。 */
public record FlagRuleResponse(
        Integer version,
        String flagCode,
        Integer maxEvidenceAgeDays,
        List<FlagRuleItemView> items
) {

    /** 规则版本内的项目映射视图。 */
    public record FlagRuleItemView(String itemCode, String surveyType) {
    }

    public static FlagRuleResponse from(FlagRule rule, List<FlagRuleItem> items) {
        return new FlagRuleResponse(rule.version(), rule.flagCode(), rule.maxEvidenceAgeDays(),
                items.stream()
                        .map(i -> new FlagRuleItemView(i.itemCode(), i.surveyType().name()))
                        .toList());
    }
}
