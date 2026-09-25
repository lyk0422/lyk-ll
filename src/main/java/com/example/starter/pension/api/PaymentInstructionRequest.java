package com.example.starter.pension.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 单期支付指令请求。
 *
 * @param requestId 指令幂等键；每期出款成功后该键永久对应首次结果
 * @param planNo    支付计划号
 * @param seqNo     期次序号，从 1 开始
 * @param forceFail 是否模拟托管行出款失败（true 时记录失败、不出款，可用新 requestId 重试）
 */
public record PaymentInstructionRequest(
        @NotBlank String requestId,
        @NotBlank String planNo,
        @Min(1) int seqNo,
        boolean forceFail) {
}
