package com.example.starter.pension.po;

import com.example.starter.pension.domain.ClaimStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * inheritance_claim 表行对象。
 *
 * @param id                       主键
 * @param claimNo                  继承申领号
 * @param requestId                申领幂等键
 * @param memberId                 身故成员 ID
 * @param heirName                 继承人姓名
 * @param deathDate                成员身故日（UTC 日历日）
 * @param applyDate                申领受理日（UTC 日界）
 * @param fingerprint              申领指纹
 * @param accountBalanceCents      账户余额构成，单位：分
 * @param unpaidInstallmentCents   未支付分期本金构成，单位：分
 * @param claimAmountCents         继承支付额=两项构成合计，单位：分
 * @param terminatedPlanId         被终止的支付计划 ID；null 表示无进行中计划
 * @param status                   申领状态
 * @param paidAt                   继承支付完成时间（UTC）；未完成为 null
 */
public record ClaimRow(
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
        long claimAmountCents,
        Long terminatedPlanId,
        ClaimStatus status,
        OffsetDateTime paidAt) {
}
