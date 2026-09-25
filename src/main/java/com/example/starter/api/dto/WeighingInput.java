package com.example.starter.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 单项实际称量（克）；目标克重由服务端按放大配方计算，不接受客户端传入。
 */
public record WeighingInput(
        @NotBlank String dyeCode,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal actualG) {
}
