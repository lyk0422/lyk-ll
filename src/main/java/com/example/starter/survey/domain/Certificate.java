package com.example.starter.survey.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 船级证书版本；按“船＋检验类型”唯一有效。
 *
 * @param id           主键
 * @param shipId       船舶主键
 * @param type         检验类型
 * @param version      版本号，首版 1，新证递增
 * @param status       VALID / SUPERSEDED / EXPIRED
 * @param validFrom    签发日（UTC）
 * @param validUntil   有效期至（UTC，展期批准与该字段变更同事务）
 * @param surveyId     来源检验
 * @param supersededBy 被哪一版证书替代，null 表示未被替代
 * @param createdAt    创建时刻（UTC）
 */
public record Certificate(
        Long id,
        Long shipId,
        SurveyType type,
        int version,
        CertificateStatus status,
        LocalDate validFrom,
        LocalDate validUntil,
        Long surveyId,
        Long supersededBy,
        Instant createdAt
) {
}
