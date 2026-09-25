package com.example.starter.domain;

import java.time.Instant;

/**
 * 证书行（按批次唯一）。
 *
 * @param id          主键
 * @param certNo      证书号，按批次唯一
 * @param lotId       批次主键
 * @param grade       证书等级
 * @param labelCount  本证核减/配发标签数量，单位：枚
 * @param status      证书状态
 * @param issuedAt    发证时间（UTC）
 * @param voidedAt    作废时间（UTC）；空表示未作废
 * @param voidReason  作废原因；批次注销时同步作废并留痕
 * @param createdAt   入库时间（UTC）
 */
public record Certificate(
        Long id,
        String certNo,
        long lotId,
        Grade grade,
        int labelCount,
        CertStatus status,
        Instant issuedAt,
        Instant voidedAt,
        String voidReason,
        Instant createdAt) {
}
