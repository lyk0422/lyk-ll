package com.example.starter.pension.dto;

import com.example.starter.pension.domain.InstallmentType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 补发申请：结清后补缴的历史缴费必须引用原计划号，走补发流程，不得改动原计划。
 *
 * @param requestId       补发申请幂等键
 * @param originalPlanNo  原支付计划号（必须已结清）
 * @param installmentType 补发计划的分期方式
 */
public record SupplementRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "原计划号不能为空") String originalPlanNo,
        @NotNull(message = "分期方式不能为空") InstallmentType installmentType) {
}
