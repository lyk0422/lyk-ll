package com.example.starter.survey.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 申请互认：把一条外部报告修订中的一个检查项目，
 * 绑定为当前本船未签发检验中映射船级项目的完成证明。
 *
 * @param surveyId      当前本船未签发检验
 * @param reportNo      引用的报告编号
 * @param ruleVersion   客户端选择冻结的规则版本（必须为存在的版本）
 * @param flagItemCode  报告中的船旗国检查项目代码
 */
public record BindRecognitionRequest(
        @NotBlank String requestId,
        @NotNull Long surveyId,
        @NotBlank String reportNo,
        @NotNull Integer ruleVersion,
        @NotBlank String flagItemCode
) {
}
