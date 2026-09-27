package com.example.starter.survey.domain;

import java.time.Instant;

/**
 * 互认绑定：将外部报告修订的合格项目绑定到本船未签发检验的船级项目。
 * 报告修订与规则版本在绑定时冻结。
 *
 * @param id            主键
 * @param requestId     申请幂等键
 * @param surveyId      本船未签发检验主键
 * @param reportId      冻结的报告修订主键
 * @param ruleId        冻结的规则版本主键
 * @param flagItemCode  船旗检查项目代码
 * @param classItemCode 船级检查项目代码
 * @param status        绑定状态
 * @param createdAt     创建时刻（UTC）
 * @param invalidatedAt 失效时刻（UTC），未失效为 null
 */
public record RecognitionBinding(
        Long id,
        String requestId,
        Long surveyId,
        Long reportId,
        Long ruleId,
        String flagItemCode,
        String classItemCode,
        RecognitionStatus status,
        Instant createdAt,
        Instant invalidatedAt
) {
}
