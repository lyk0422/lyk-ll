package com.example.starter.web;

import java.time.Instant;

/**
 * 证书视图。
 *
 * @param certNo     证书号
 * @param grade      证书等级
 * @param labelCount 本证核减/配发标签数量（枚）
 * @param status     证书状态：VALID 有效/VOID 已作废
 * @param issuedAt   发证时间（UTC）
 * @param voidedAt   作废时间（UTC）；空表示未作废
 * @param voidReason 作废原因；空表示未作废
 */
public record CertView(
        String certNo,
        String grade,
        int labelCount,
        String status,
        Instant issuedAt,
        Instant voidedAt,
        String voidReason) {
}
