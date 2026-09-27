package com.example.starter.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 批次系数修订请求：携带 expectedVersion 乐观校验，版本从 1 开始、成功变更加 1；
 * 修订使引用该批次的未投料补偿批准失效，已投料历史不追改。
 */
public record ReviseFactorRequest(
        @NotBlank String requestId,
        @NotNull @Min(1) Integer expectedVersion,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal factor) {
}
