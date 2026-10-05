package com.example.starter.pension.po;

import com.example.starter.pension.domain.InheritanceStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * inheritance_claim 表行对象。
 *
 * @param id                       主键
 * @param claimNo                  继承申领号
 * @param requestId                申领幂等键
 * @param memberId                 成员 ID
 * @param heirName                 继承人姓名
 * @param deathDate                成员身故日（UTC 日历日）
 * @param applyDate                继承申请日（UTC 日历日）
 * @param fingerprint              申请指纹
 * @param accountBalanceCents      账户余额快照，单位：分
 * @param unpaidInstallmentCents   未支付分期本金之和，单位：分
 * @param inheritanceAmountCents   继承支付额，单位：分
 * @param terminatedPlanId         被终止的进行中计划 ID；无为 null
 * @param status                   申领状态
 * @param activeMemberId           占用的成员 ID；全程不释放
 * @param completedAt              继承支付完成时间（UTC）；未完成为 null
 */
public record InheritanceClaimRow(
        long id,
        String claimNo,
        String requestId,
        long memberId,
        String heirName,
        LocalDate deathDate,
        LocalDate applyDate,
        String fingerprint,
        long accountBalanceCents,
        long unpaidInstallmentCents,
        long inheritanceAmountCents,
        Long terminatedPlanId,
        InheritanceStatus status,
        Long activeMemberId,
        OffsetDateTime completedAt) {
}
