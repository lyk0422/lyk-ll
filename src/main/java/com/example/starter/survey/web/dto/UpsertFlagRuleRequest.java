package com.example.starter.survey.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 新建/变更船旗互认规则版本；requestId 幂等。
 * expectedVersion 为变更前当前版本（无历史版本时为 0），成功变更后版本加 1。
 */
public record UpsertFlagRuleRequest(
        @NotBlank String requestId,
        @NotBlank String flagCode,
        @NotNull @Min(0) Integer expectedVersion,
        @NotNull @Min(1) Integer maxEvidenceAgeDays,
        @NotEmpty List<@Valid FlagRuleMappingInput> mappings
) {
}
