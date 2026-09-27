package com.example.starter.survey.web.dto;

import java.util.List;

/** 证据链视图：报告全部修订 + 关联互认绑定 + 风险记录。 */
public record EvidenceChainResponse(
        String reportNo,
        List<ReportResponse> revisions,
        List<BindingResponse> bindings,
        List<RiskResponse> risks
) {
}
