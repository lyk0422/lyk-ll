package com.example.starter.pension.api;

import com.example.starter.pension.domain.InstallmentType;
import com.example.starter.pension.domain.PayoutReason;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * 补发申请请求：原计划结清后录入的历史缴费，须走补发流程并引用原计划号。
 *
 * @param requestId       补发申请幂等键（新键）
 * @param memberNo        成员业务编号
 * @param originalPlanNo  原支付计划号，必须为已结清计划
 * @param reason          支付事由（与成员当前状态一致）
 * @param installmentType 分期方式
 * @param requestDate     补发申请日（UTC 日历日）
 */
public record SupplementaryPayoutRequest(
        @NotBlank String requestId,
        @NotBlank String memberNo,
        @NotBlank String originalPlanNo,
        @NotNull PayoutReason reason,
        @NotNull InstallmentType installmentType,
        @NotNull LocalDate requestDate) {
}
