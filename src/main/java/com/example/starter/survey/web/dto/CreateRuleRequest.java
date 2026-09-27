package com.example.starter.survey.web.dto;

import com.example.starter.survey.domain.SurveyItem;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建合成互认规则版本；requestId 幂等。
 *
 * @param ruleVersion     规则版本号（全局唯一，从 1 开始）
 * @param flagCode        适用船旗代码（合成）
 * @param maxEvidenceDays 证据最长有效天数（&gt;0）
 * @param items           映射的船级检查项目集合（非空、去重由服务校验）
 */
public record CreateRuleRequest(
        @NotBlank String requestId,
        @NotNull @Positive Integer ruleVersion,
        @NotBlank @Size(max = 8) String flagCode,
        @NotNull @Positive Integer maxEvidenceDays,
        @NotNull @NotEmpty List<SurveyItem> items
) {
}
