package com.example.starter.pension.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * 继承申领请求：继承人就身故成员申请一次性继承支付。
 *
 * @param requestId                     申领幂等键
 * @param memberNo                      身故成员业务编号
 * @param heirName                      继承人姓名（合成数据）
 * @param deathDate                     成员身故日（UTC 日历日），申领窗口为身故日起 180 个自然日（含第 180 日）
 * @param declaredAccountBalanceCents   申报的账户余额，单位：分，须与系统核算一致
 * @param declaredUnpaidInstallmentCents 申报的未支付分期本金之和，单位：分，须与系统核算一致
 * @param declaredClaimAmountCents      申报的继承支付额，单位：分，须等于两项构成合计
 */
public record InheritanceClaimRequest(
        @NotBlank String requestId,
        @NotBlank String memberNo,
        @NotBlank String heirName,
        @NotNull LocalDate deathDate,
        @Min(0) long declaredAccountBalanceCents,
        @Min(0) long declaredUnpaidInstallmentCents,
        @Min(0) long declaredClaimAmountCents) {
}
