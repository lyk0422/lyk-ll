package com.example.starter.survey.domain;

import java.time.Instant;

/**
 * 互认绑定：把报告某项目结论绑定到未签发检验，冻结报告修订与规则版本。
 *
 * @param id            主键
 * @param requestId     申请幂等键
 * @param surveyId      检验主键（申请时须未签发）
 * @param reportId      报告头主键
 * @param revision      绑定时冻结的报告修订号
 * @param ruleVersion   绑定时冻结的规则版本号
 * @param itemCode      船旗检查项目代码（每个检验每个项目只一个有效绑定）
 * @param status        ACTIVE 有效 / INVALIDATED 已失效
 * @param createdAt     创建时刻（UTC）
 * @param invalidatedAt 失效时刻（UTC），未失效为 null
 * @param fingerprint   业务指纹（requestId + 全部业务字段）
 */
public record FlagRecognition(
        Long id,
        String requestId,
        Long surveyId,
        Long reportId,
        Integer revision,
        Integer ruleVersion,
        String itemCode,
        RecognitionStatus status,
        Instant createdAt,
        Instant invalidatedAt,
        String fingerprint
) {
}
