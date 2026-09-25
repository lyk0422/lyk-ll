package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 证书视图。
 *
 * @param certificateNo  证书号（批次唯一）
 * @param lotNo          批次编号
 * @param grade          ORIGINAL=原种，IMPROVED=良种
 * @param deductedLabels 发证核减标签数量（张）
 * @param status         VALID=有效，REVOKED=已作废
 * @param issuedAt       发证时间（UTC）
 * @param revokedAt      作废时间（UTC），空值表示有效
 */
public record CertificateView(
        String certificateNo,
        String lotNo,
        String grade,
        int deductedLabels,
        String status,
        Instant issuedAt,
        Instant revokedAt
) {
}
