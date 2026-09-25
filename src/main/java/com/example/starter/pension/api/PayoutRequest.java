package com.example.starter.pension.api;

import com.example.starter.pension.domain.InstallmentType;
import com.example.starter.pension.domain.PayoutReason;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * 支付申请请求。
 *
 * @param requestId       申请幂等键
 * @param memberNo        成员业务编号
 * @param reason          支付事由 RESIGN/RETIRE
 * @param installmentType 分期方式 LUMP_SUM/MONTHLY_12/MONTHLY_24
 * @param requestDate     支付申请日（UTC 日历日），司龄计算基准日
 */
public record PayoutRequest(
        @NotBlank String requestId,
        @NotBlank String memberNo,
        @NotNull PayoutReason reason,
        @NotNull InstallmentType installmentType,
        @NotNull LocalDate requestDate) {
}
