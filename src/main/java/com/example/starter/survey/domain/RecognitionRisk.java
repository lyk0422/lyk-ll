package com.example.starter.survey.domain;

import java.time.Instant;

/**
 * 互认风险记录：报告更正波及已签发检验的历史互认时登记；历史证据不修改。
 *
 * @param id        主键
 * @param bindingId 受影响的互认绑定主键
 * @param surveyId  已签发检验主键
 * @param reportNo  报告编号
 * @param note      风险说明
 * @param createdAt 登记时刻（UTC）
 */
public record RecognitionRisk(
        Long id,
        Long bindingId,
        Long surveyId,
        String reportNo,
        String note,
        Instant createdAt
) {
}
