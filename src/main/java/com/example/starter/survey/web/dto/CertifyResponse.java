package com.example.starter.survey.web.dto;

/**
 * 发证结果。
 *
 * @param survey                 终态检验
 * @param certificate            新签发/已有的有效证书
 * @param supersededCertificateId 被替代的旧证书 ID，首版或重复签发时为 null
 */
public record CertifyResponse(
        SurveyResponse survey,
        CertificateResponse certificate,
        Long supersededCertificateId
) {
}
