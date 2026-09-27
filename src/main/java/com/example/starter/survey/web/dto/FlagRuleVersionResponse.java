package com.example.starter.survey.web.dto;

import com.example.starter.survey.domain.FlagRuleItem;
import com.example.starter.survey.domain.FlagRuleVersion;

import java.util.List;

/** 互认规则版本视图（含项目映射）。 */
public record FlagRuleVersionResponse(
        Long id,
        String flagCode,
        int version,
        int maxEvidenceAgeDays,
        List<FlagRuleMappingInput> mappings,
        String createdAt
) {

    /** 将领域记录与映射转换为视图。 */
    public static FlagRuleVersionResponse from(FlagRuleVersion rule, List<FlagRuleItem> items) {
        return new FlagRuleVersionResponse(
                rule.id(), rule.flagCode(), rule.version(), rule.maxEvidenceAgeDays(),
                items.stream()
                        .map(i -> new FlagRuleMappingInput(i.flagItemCode(), i.classItemCode()))
                        .toList(),
                rule.createdAt() == null ? null : rule.createdAt().toString());
    }
}
