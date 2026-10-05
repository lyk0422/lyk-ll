package com.example.starter.pension.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * 继承申领请求。继承人自成员身故日起 180 个自然日（UTC 日界，含第 180 日）内可申请。
 *
 * @param requestId         申领幂等键；同键同参返回首次结果，同键异参 409
 * @param memberNo          身故成员业务编号
 * @param heirName          继承人姓名（合成数据）
 * @param deathDate         成员身故日（UTC 日历日）
 * @param applyDate         继承申请日（UTC 日历日），180 日窗口判定基准
 * @param claimAmountCents  继承人申报的继承支付额，单位：分；
 *                          须等于 账户余额+未支付分期本金，不等拒绝
 */
public record InheritanceApplyRequest(
        @NotBlank String requestId,
        @NotBlank String memberNo,
        @NotBlank String heirName,
        @NotNull LocalDate deathDate,
        @NotNull LocalDate applyDate,
        @Min(0) long claimAmountCents) {
}
