package com.example.starter.survey.web.dto;

import com.example.starter.survey.domain.SurveyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 规则版本中的一条项目映射：船旗国检查项目 → 某检验类型下的船级检验项目。
 */
public record RuleMappingInput(
        @NotBlank String flagItemCode,
        @NotNull SurveyType surveyType,
        @NotBlank String classItemCode
) {
}
