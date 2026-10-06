package com.example.starter.trace.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 药残快检请求。抑制率达到 50.00 判疑似并冻结采收批。
 */
public record RapidTestRequest(
        @NotBlank String requestId,
        @NotBlank String sampleNo,
        @NotNull
        @DecimalMin(value = "0.00", message = "抑制率不得为负")
        @DecimalMax(value = "100.00", message = "抑制率不得超过 100")
        @Digits(integer = 3, fraction = 2, message = "抑制率最多两位小数")
        BigDecimal inhibitionRate) {
}
