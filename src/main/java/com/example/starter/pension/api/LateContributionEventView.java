package com.example.starter.pension.api;

import java.time.OffsetDateTime;

/**
 * 继承支付完成后迟到缴费事件视图（提示人工复核）。
 *
 * @param eventId        事件 ID
 * @param contributionId 迟到缴费记录 ID
 * @param claimNo        已完成继承支付的申领号
 * @param amountCents    迟到缴费金额，单位：分
 * @param note           事件说明
 * @param createdAt      记录时间（UTC）
 */
public record LateContributionEventView(
        long eventId,
        long contributionId,
        String claimNo,
        long amountCents,
        String note,
        OffsetDateTime createdAt) {
}
