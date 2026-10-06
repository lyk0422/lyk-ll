package com.example.starter.waste;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 处理设施回执请求。
 *
 * @param requestId 幂等键
 * @param receivedNetKg 设施签收净重，单位千克
 */
public record ReceiptRequest(
        @NotBlank String requestId,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal receivedNetKg) {
}
