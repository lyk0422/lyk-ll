package com.example.starter.survey.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 发布新的合成互认规则版本。
 *
 * @param expectedVersion        客户端持有的当前最新版本号；尚无版本时传 0，成功后新版本为该值 +1
 * @param maxEvidenceValidDays   外部证据最长有效天数（自然日，至少 1）
 * @param flagCodes              该版本承认的合成船旗代码集合
 * @param mappings               船旗国检查项目 → 船级检验项目映射集合（版本内项目代码唯一）
 */
public record PublishRuleRequest(
        @NotBlank String requestId,
        @NotNull @Min(0) Integer expectedVersion,
        @NotNull @Min(1) Integer maxEvidenceValidDays,
        @NotEmpty List<@NotBlank String> flagCodes,
        @NotEmpty @Valid List<RuleMappingInput> mappings,
        String note
) {
}
