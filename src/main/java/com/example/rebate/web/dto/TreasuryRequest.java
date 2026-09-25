package com.example.rebate.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 退库核销请求。批次＋国库流水号唯一登记。
 *
 * @param receivedAmount 实际到账金额
 */
public record TreasuryRequest(
        @NotBlank String requestId,
        @NotBlank String treasurySerial,
        @NotNull @DecimalMin("0.0") BigDecimal receivedAmount) {
}
