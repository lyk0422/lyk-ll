package com.example.starter.pension.dto;

import com.example.starter.pension.domain.InstallmentType;
import com.example.starter.pension.domain.PayoutReason;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 支付申请请求。
 *
 * @param requestId       幂等键
 * @param memberNo        成员编号
 * @param reason          支付事由：RESIGNED 离职 / RETIRED 退休
 * @param installmentType 分期方式：LUMP_SUM / MONTHLY_12 / MONTHLY_24
 */
public record PayoutRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "成员编号不能为空") String memberNo,
        @NotNull(message = "支付事由不能为空") PayoutReason reason,
        @NotNull(message = "分期方式不能为空") InstallmentType installmentType) {
}
