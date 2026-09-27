package com.example.starter.survey.web.dto;

import com.example.starter.survey.domain.RecognitionRule;
import com.example.starter.survey.domain.SurveyItem;

import java.util.List;

/** 互认规则版本视图。 */
public record RuleResponse(
        Long id,
        int ruleVersion,
        String flagCode,
        int maxEvidenceDays,
        List<SurveyItem> items,
        String createdAt
) {

    /** 将领域记录与项目集合转换为视图。 */
    public static RuleResponse from(RecognitionRule rule, List<SurveyItem> items) {
        return new RuleResponse(rule.id(), rule.ruleVersion(), rule.flagCode(),
                rule.maxEvidenceDays(), items, rule.createdAt().toString());
    }
}
