package com.example.starter.survey.domain;

import java.time.Instant;

/**
 * 风险记录：报告更正时已签发检验的历史互认不改动，另记风险备查。
 *
 * @param id            主键
 * @param recognitionId 受影响互认绑定主键
 * @param surveyId      已签发检验主键
 * @param reportId      报告头主键
 * @param oldRevision   绑定冻结的旧修订号
 * @param newRevision   更正产生的新修订号
 * @param note          风险说明
 * @param createdAt     记录时刻（UTC）
 */
public record FlagRiskNote(
        Long id,
        Long recognitionId,
        Long surveyId,
        Long reportId,
        Integer oldRevision,
        Integer newRevision,
        String note,
        Instant createdAt
) {
}
