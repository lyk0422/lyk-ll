package com.example.starter.survey.domain;

/**
 * 船旗检查项目到船级检查项目的映射（限定互认范围）。
 *
 * @param id            主键
 * @param ruleId        所属规则版本主键
 * @param flagItemCode  船旗检查项目代码
 * @param classItemCode 船级检查项目代码
 */
public record FlagRuleItem(
        Long id,
        Long ruleId,
        String flagItemCode,
        String classItemCode
) {
}
