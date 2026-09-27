package com.example.starter.survey.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 申请限定范围互认：引用报告（最新修订）、本船未签发检验与规则版本；requestId 幂等。
 * 每个船级项目只保留一个有效绑定。
 */
public record ApplyRecognitionRequest(
        @NotBlank String requestId,
        @NotBlank String reportNo,
        @NotNull Long surveyId,
        @NotNull @Min(1) Integer ruleVersion,
        @NotEmpty List<@NotBlank String> classItems
) {
}
