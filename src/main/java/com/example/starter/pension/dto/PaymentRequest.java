package com.example.starter.pension.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 单期支付指令请求。
 *
 * @param requestId 幂等键（每期支付指令各自幂等）
 * @param planNo    支付计划号
 * @param seqNo     期次序号，从 1 开始
 * @param forceFail 是否强制模拟托管行失败（用于演示失败重试）
 */
public record PaymentRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "计划号不能为空") String planNo,
        @Min(value = 1, message = "期次序号从 1 开始") int seqNo,
        @NotNull(message = "forceFail 不能为空") Boolean forceFail) {
}
