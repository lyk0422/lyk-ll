package com.example.starter.fermentation.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 双乙酰检测上报请求，requestId 幂等；含量单位 mg/L。
 */
public record DiacetylRequest(
        @NotBlank String requestId,
        @NotBlank String batchNo,
        @NotNull Instant testedAt,
        @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal valueMgL) {
}
