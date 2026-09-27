package com.example.starter.survey.domain;

/**
 * 船旗国检查项目 → 船级检验项目映射（规则版本内逐项配置）。
 *
 * @param id             主键
 * @param ruleVersionId  所属规则版本主键
 * @param flagItemCode   船旗国检查项目代码
 * @param surveyType     映射到的船级检验类型
 * @param classItemCode  映射替代的船级检验项目代码
 */
public record RecognitionItemMapping(
        Long id,
        Long ruleVersionId,
        String flagItemCode,
        SurveyType surveyType,
        String classItemCode
) {
}
