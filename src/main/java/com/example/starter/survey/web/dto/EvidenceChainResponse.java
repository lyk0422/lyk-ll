package com.example.starter.survey.web.dto;

import java.util.List;

/**
 * 证据链视图：互认绑定及其冻结的报告修订（含逐项结论）与规则版本（含映射），
 * 以及已签发历史证据的风险记录。
 */
public record EvidenceChainResponse(
        Long surveyId,
        List<Entry> entries,
        List<RecognitionRiskResponse> risks
) {

    /** 单条证据链：绑定 + 冻结报告修订 + 冻结规则版本。 */
    public record Entry(
            RecognitionBindingResponse binding,
            FlagReportResponse report,
            FlagRuleVersionResponse rule
    ) {
    }
}
