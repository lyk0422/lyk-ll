package com.example.starter.pension.api;

import com.example.starter.pension.domain.ClaimStatus;

import java.time.OffsetDateTime;

/**
 * 继承支付完成响应。
 *
 * @param claimNo      继承申领号
 * @param status       申领状态（成功或幂等重放后为 PAID 终态）
 * @param amountCents  继承支付额，单位：分
 * @param success      本次出款是否成功；false 时申领保持 ACCEPTED 可重试
 * @param replayed     是否为重复完成的幂等成功
 * @param paidAt       继承支付完成时间（UTC）；未完成为 null
 */
public record InheritanceCompleteResponse(
        String claimNo,
        ClaimStatus status,
        long amountCents,
        boolean success,
        boolean replayed,
        OffsetDateTime paidAt) {
}
