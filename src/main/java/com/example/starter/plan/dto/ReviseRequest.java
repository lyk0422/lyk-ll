package com.example.starter.plan.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 计划换版请求：新处方数据生成新版本，旧版本同事务失效。
 */
public record ReviseRequest(
        @NotNull @DecimalMin("0.01") BigDecimal prescriptionTotalGy,
        @Min(1) int fractionCount,
        @NotNull @DecimalMin("0.01") BigDecimal fractionDoseGy,
        @NotEmpty List<@Valid OarDose> oarDoses) {
}
