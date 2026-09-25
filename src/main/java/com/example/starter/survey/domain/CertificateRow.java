package com.example.starter.survey.domain;

import com.example.starter.survey.model.SurveyEnums.CertificateStatus;

import java.time.LocalDate;

/**
 * 证书版本行。{@code validFlag} TRUE 表示按“船＋检验类型”唯一的当前有效证书。
 */
public record CertificateRow(
        Long id,
        String certNo,
        Long shipId,
        String typeCode,
        Long surveyId,
        int version,
        CertificateStatus status,
        LocalDate validFrom,
        LocalDate validUntil,
        LocalDate recheckDue,
        Boolean validFlag,
        LocalDate issuedAt,
        LocalDate invalidatedAt) {
}
