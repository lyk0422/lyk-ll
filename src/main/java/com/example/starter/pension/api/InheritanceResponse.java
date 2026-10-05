package com.example.starter.pension.api;

import com.example.starter.pension.domain.InheritanceStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 继承申领视图，受理、重放与查询均返回同一内容；勾稽明细可回源表复算。
 *
 * @param claimNo                  继承申领号
 * @param requestId                申领幂等键
 * @param memberNo                 身故成员业务编号
 * @param heirName                 继承人姓名
 * @param deathDate                成员身故日（UTC）
 * @param applyDate                继承申请日（UTC）
 * @param accountBalanceCents      账户余额快照，单位：分
 * @param unpaidInstallmentCents   未支付分期本金之和，单位：分
 * @param inheritanceAmountCents   继承支付额=账户余额+未支付分期本金，单位：分
 * @param terminatedPlanNo         因受理继承被终止的进行中计划号；无为 null
 * @param status                   申领状态
 * @param completedAt              继承支付完成时间（UTC）；未完成为 null
 * @param reconciliation           勾稽明细
 */
public record InheritanceResponse(
        String claimNo,
        String requestId,
        String memberNo,
        String heirName,
        LocalDate deathDate,
        LocalDate applyDate,
        long accountBalanceCents,
        long unpaidInstallmentCents,
        long inheritanceAmountCents,
        String terminatedPlanNo,
        InheritanceStatus status,
        OffsetDateTime completedAt,
        List<ReconView> reconciliation) {

    /**
     * 单条勾稽明细。
     *
     * @param component   勾稽组成：ACCOUNT_BALANCE 账户余额 / UNPAID_INSTALLMENT 未支付分期本金
     * @param sourceType  来源类型：CONTRIBUTION 缴费 / INSTALLMENT 分期期次
     * @param sourceId    来源行主键，可回源表复算
     * @param amountCents 计入继承金额的金额，单位：分
     */
    public record ReconView(
            String component,
            String sourceType,
            long sourceId,
            long amountCents) {
    }
}
