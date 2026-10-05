package com.example.starter.pension.po;

import com.example.starter.pension.domain.InstallmentType;
import com.example.starter.pension.domain.PlanStatus;
import com.example.starter.pension.domain.PayoutReason;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * payout_plan 表行对象。
 *
 * @param id                      主键
 * @param planNo                  计划号
 * @param requestId               申请幂等键
 * @param memberId                成员 ID
 * @param reason                  支付事由
 * @param installmentType         分期方式
 * @param fingerprint             申请指纹
 * @param requestDate             申请日（UTC）
 * @param hireYears               司龄整年数
 * @param vestedRatioBp           企业缴费归属比例（基点）
 * @param personalAmountCents     个人缴费快照金额，单位：分
 * @param enterpriseAmountCents   企业缴费快照金额，单位：分
 * @param vestedEnterpriseCents   归属企业缴费金额，单位：分
 * @param vestedTotalCents        归属权益快照总额，单位：分
 * @param unvestedReturnCents     未归属划回金额，单位：分
 * @param installmentsCount       分期总期数
 * @param status                  计划状态
 * @param activeMemberId          进行中占用成员 ID；null 表示不占用
 * @param enterpriseReturned      未归属金额是否已划回
 * @param originalPlanNo          补发引用的原计划号；null 为原始申请
 * @param supplementary           是否补发计划
 * @param settledAt               结清时间（UTC）；未结清为 null
 * @param terminatedAt            被继承申领终止的时间（UTC）；未终止为 null
 */
public record PlanRow(
        long id,
        String planNo,
        String requestId,
        long memberId,
        PayoutReason reason,
        InstallmentType installmentType,
        String fingerprint,
        LocalDate requestDate,
        int hireYears,
        int vestedRatioBp,
        long personalAmountCents,
        long enterpriseAmountCents,
        long vestedEnterpriseCents,
        long vestedTotalCents,
        long unvestedReturnCents,
        int installmentsCount,
        PlanStatus status,
        Long activeMemberId,
        boolean enterpriseReturned,
        String originalPlanNo,
        boolean supplementary,
        OffsetDateTime settledAt,
        OffsetDateTime terminatedAt) {
}
