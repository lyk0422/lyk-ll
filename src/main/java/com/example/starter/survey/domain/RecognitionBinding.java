package com.example.starter.survey.domain;

import java.time.Instant;

/**
 * 互认绑定：把一条外部报告修订中的一个船旗国检查项目，
 * 绑定为某次船级检验中一个映射船级项目的完成证明。
 *
 * @param id                 主键
 * @param requestId          绑定申请幂等键
 * @param surveyId           当前本船未签发检验
 * @param reportId           引用的报告修订行（绑定时冻结）
 * @param reportNo           报告编号
 * @param revisionNo         绑定时冻结的报告修订号
 * @param ruleVersion        绑定时冻结的规则版本号
 * @param flagItemCode       船旗国检查项目代码
 * @param surveyType         检验类型
 * @param classItemCode      映射替代的船级检验项目代码
 * @param status             ACTIVE / INVALIDATED
 * @param invalidatedReason  失效原因代码（如 REPORT_REVISED），有效时为 null
 * @param createdAt          创建时刻（UTC）
 * @param invalidatedAt      失效时刻（UTC），有效时为 null
 * @param fingerprint        业务指纹
 */
public record RecognitionBinding(
        Long id,
        String requestId,
        Long surveyId,
        Long reportId,
        String reportNo,
        int revisionNo,
        int ruleVersion,
        String flagItemCode,
        SurveyType surveyType,
        String classItemCode,
        RecognitionBindingStatus status,
        String invalidatedReason,
        Instant createdAt,
        Instant invalidatedAt,
        String fingerprint
) {
}
