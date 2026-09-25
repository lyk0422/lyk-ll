package com.example.starter.survey.web.dto;

import com.example.starter.survey.domain.Certificate;
import com.example.starter.survey.domain.CertificateStatus;
import com.example.starter.survey.domain.SurveyType;

/** 证书视图。 */
public record CertificateResponse(
        Long id,
        String shipImo,
        SurveyType type,
        int version,
        CertificateStatus status,
        String validFrom,
        String validUntil,
        Long surveyId,
        Long supersededBy
) {

    public static CertificateResponse from(Certificate cert, String shipImo) {
        return new CertificateResponse(
                cert.id(), shipImo, cert.type(), cert.version(), cert.status(),
                cert.validFrom().toString(),
                cert.validUntil() == null ? null : cert.validUntil().toString(),
                cert.surveyId(), cert.supersededBy()
        );
    }
}
