package com.example.starter.survey.domain;

/**
 * 规则版本内的船旗检查项目映射项。
 *
 * @param id         主键
 * @param ruleId     所属规则版本主键
 * @param itemCode   船旗检查项目代码
 * @param surveyType 映射目标船级检验类型
 */
public record FlagRuleItem(
        Long id,
        Long ruleId,
        String itemCode,
        SurveyType surveyType
) {
}
