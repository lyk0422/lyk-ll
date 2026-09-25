package com.example.starter.domain;

import java.time.Instant;

/**
 * 认证证书；每批次唯一。
 *
 * @param id             主键
 * @param certificateNo  证书号（按批次唯一生成）
 * @param lotNo          批次编号
 * @param grade          证书等级
 * @param deductedLabels 发证核减的标签数量（张）
 * @param state          证书状态
 * @param issuedAt       发证时间（UTC）
 * @param revokedAt      作废时间（UTC），空值表示有效
 */
public record Certificate(
        Long id,
        String certificateNo,
        String lotNo,
        SeedGrade grade,
        int deductedLabels,
        CertificateState state,
        Instant issuedAt,
        Instant revokedAt
) {
}
