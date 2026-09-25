package com.example.starter.transition.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 过渡纸卷登记请求：按卷号唯一登记，登记即强制降级，不得按正品入库。
 *
 * @param requestId 幂等键
 * @param rollNo    卷号（唯一）
 * @param orderNo   所属改品种单号
 * @param lengthM   纸卷长度，单位米
 */
public record RegisterRollRequest(
        @NotBlank String requestId,
        @NotBlank String rollNo,
        @NotBlank String orderNo,
        @NotNull @DecimalMin("0.0") BigDecimal lengthM
) {
}
