package com.example.starter.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 出缸判定请求：以本缸 ΔE 与同配方对比缸 ΔE 计算缸差；
 * 差超 0.5 须回修一次，回修后仍超差转降级。
 */
public record JudgeRequest(
        @NotBlank String requestId,
        @NotBlank String lotNo,
        @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal deltaE,
        String referenceLotNo) {
}
