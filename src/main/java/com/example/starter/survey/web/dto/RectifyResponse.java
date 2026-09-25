package com.example.starter.survey.web.dto;

/**
 * 缺陷复查结果。
 *
 * @param defect                处理后的缺陷
 * @param accepted              是否按期受理（false 表示逾期不予受理）
 * @param invalidatedCertificateId 逾期导致失效的证书 ID，无则为 null
 */
public record RectifyResponse(
        DefectResponse defect,
        boolean accepted,
        Long invalidatedCertificateId
) {
}
