package com.example.starter.pension.po;

import java.time.OffsetDateTime;

/**
 * late_contribution_event 表行对象（继承支付完成后迟到的缴费事件）。
 *
 * @param id             主键
 * @param memberId       成员 ID
 * @param contributionId 迟到缴费记录 ID
 * @param claimId        已完成继承支付的申领 ID
 * @param amountCents    迟到缴费金额，单位：分
 * @param note           事件说明，提示人工复核
 * @param createdAt      记录时间（UTC）
 */
public record LateContributionEventRow(
        long id,
        long memberId,
        long contributionId,
        long claimId,
        long amountCents,
        String note,
        OffsetDateTime createdAt) {
}
