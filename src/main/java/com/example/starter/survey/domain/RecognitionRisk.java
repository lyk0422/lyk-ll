package com.example.starter.survey.domain;

import java.time.Instant;

/**
 * 互认风险记录：报告更正时若相关证据已被已签发检验使用，
 * 已签发历史证据不可更改，另记风险。
 *
 * @param id              主键
 * @param reportNo        报告编号
 * @param oldRevisionNo   被替代的旧修订号
 * @param newRevisionNo   新生效修订号
 * @param surveyId        受影响的已签发检验（历史数据缺失时可能为 null）
 * @param reason          风险原因代码
 * @param createdAt       记录时刻（UTC）
 */
public record RecognitionRisk(
        Long id,
        String reportNo,
        int oldRevisionNo,
        int newRevisionNo,
        Long surveyId,
        String reason,
        Instant createdAt
) {
}
