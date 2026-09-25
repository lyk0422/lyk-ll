package com.example.starter.pension.api;

import com.example.starter.pension.domain.InstallmentType;
import com.example.starter.pension.domain.PlanStatus;
import com.example.starter.pension.domain.PayoutReason;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 支付计划视图，申请受理与重放均返回同一内容。
 *
 * @param planNo                 计划号
 * @param originalPlanNo         补发计划引用的原计划号；非补发为 null
 * @param supplementary          是否补发计划
 * @param requestId              申请幂等键
 * @param memberNo               成员业务编号
 * @param reason                 支付事由
 * @param installmentType        分期方式
 * @param requestDate            申请日（UTC）
 * @param hireYears              司龄整年数
 * @param vestedRatioPercent     企业缴费归属比例（百分数，如 50.00）
 * @param personalAmountCents    个人缴费快照金额，单位：分
 * @param enterpriseAmountCents  企业缴费快照金额，单位：分
 * @param vestedEnterpriseCents  归属企业缴费金额，单位：分
 * @param vestedTotalCents       归属权益快照总额，单位：分
 * @param unvestedReturnCents    未归属划回企业金额，单位：分
 * @param status                 计划状态
 * @param enterpriseReturned     未归属金额是否已划回
 * @param settledAt              结清时间（UTC）；未结清为 null
 * @param installments           各期计划
 */
public record PlanResponse(
        String planNo,
        String originalPlanNo,
        boolean supplementary,
        String requestId,
        String memberNo,
        PayoutReason reason,
        InstallmentType installmentType,
        LocalDate requestDate,
        int hireYears,
        String vestedRatioPercent,
        long personalAmountCents,
        long enterpriseAmountCents,
        long vestedEnterpriseCents,
        long vestedTotalCents,
        long unvestedReturnCents,
        PlanStatus status,
        boolean enterpriseReturned,
        OffsetDateTime settledAt,
        List<InstallmentView> installments) {

    /**
     * 单期视图。
     *
     * @param seqNo       期次序号
     * @param dueDate     到期日（月初 UTC）
     * @param amountCents 该期金额，单位：分
     * @param status      期次状态
     * @param attempts    出款尝试次数
     */
    public record InstallmentView(
            int seqNo,
            LocalDate dueDate,
            long amountCents,
            String status,
            int attempts) {
    }
}
