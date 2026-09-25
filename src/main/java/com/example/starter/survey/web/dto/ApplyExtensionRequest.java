package com.example.starter.survey.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 申请年度检验展期：仅 ANNUAL，每周期一次，1..3 个月。 */
public record ApplyExtensionRequest(
        @NotBlank String requestId,
        @NotNull Long surveyId,
        @NotNull @Min(1) @Max(3) Integer months
) {
}
