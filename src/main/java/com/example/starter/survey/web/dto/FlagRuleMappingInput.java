package com.example.starter.survey.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 船旗项目到船级项目的映射输入。 */
public record FlagRuleMappingInput(
        @NotBlank String flagItemCode,
        @NotBlank String classItemCode
) {
}
