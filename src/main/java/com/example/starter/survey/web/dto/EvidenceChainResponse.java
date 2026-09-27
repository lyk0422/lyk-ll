package com.example.starter.survey.web.dto;

import com.example.starter.survey.domain.RecognitionRisk;

import java.util.List;

/**
 * 证据链视图：报告修订链 + 各修订逐项结论 + 引用关系 + 风险记录。
 *
 * @param reportNo   报告编号
 * @param revisions  按修订号升序的修订链
 * @param bindings   引用该报告的全部绑定（含已失效）
 * @param risks      更正涉及已签发历史证据时另记的风险
 */
public record EvidenceChainResponse(
        String reportNo,
        List<ReportResponse> revisions,
        List<BindingResponse> bindings,
        List<RiskView> risks
) {

    /** 风险记录视图。 */
    public record RiskView(int oldRevisionNo, int newRevisionNo, Long surveyId,
                           String reason, String createdAt) {
        /** 领域风险记录转视图。 */
        public static RiskView from(RecognitionRisk r) {
            return new RiskView(r.oldRevisionNo(), r.newRevisionNo(), r.surveyId(),
                    r.reason(), r.createdAt() == null ? null : r.createdAt().toString());
        }
    }
}
