package com.example.starter.survey.web.dto;

import com.example.starter.survey.domain.SurveyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 申请检验；requestId 幂等。 */
public record ApplySurveyRequest(
        @NotBlank String requestId,
        @NotBlank String shipImo,
        @NotNull SurveyType type
) {
}
