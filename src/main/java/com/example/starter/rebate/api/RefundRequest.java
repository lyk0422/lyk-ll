package com.example.starter.rebate.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * 退库核销登记请求。
 *
 * @param requestId        幂等键
 * @param treasurySerialNo 国库流水号
 * @param receivedAmount   实际到账金额（元）
 */
public record RefundRequest(
        @NotBlank String requestId,
        @NotBlank String treasurySerialNo,
        @NotNull @PositiveOrZero BigDecimal receivedAmount
) {
}
