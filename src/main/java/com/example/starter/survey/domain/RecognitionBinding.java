package com.example.starter.survey.domain;

import java.time.Instant;

/**
 * 互认绑定：把报告修订中合格项目的完成证明绑定到本船未签发检验。
 * 只替代映射项目的完成证明，不替代年度窗口、重大缺陷整改与证书签发门禁。
 *
 * @param id             主键
 * @param requestId      申请幂等键
 * @param surveyId       本船未签发检验主键
 * @param reportId       报告修订主键
 * @param reportNo       报告编号
 * @param reportRevision 绑定时冻结的报告修订号
 * @param ruleId         规则主键
 * @param ruleVersion    绑定时冻结的规则版本
 * @param itemCode       互认项目代码
 * @param status         ACTIVE 有效 / INVALIDATED 已失效
 * @param fingerprint    业务指纹（requestId + 全部业务字段）
 * @param createdAt      绑定时刻（UTC）
 * @param invalidatedAt  失效时刻（UTC），有效时为 null
 */
public record RecognitionBinding(
        Long id,
        String requestId,
        Long surveyId,
        Long reportId,
        String reportNo,
        int reportRevision,
        Long ruleId,
        int ruleVersion,
        SurveyItem itemCode,
        BindingStatus status,
        String fingerprint,
        Instant createdAt,
        Instant invalidatedAt
) {
}
