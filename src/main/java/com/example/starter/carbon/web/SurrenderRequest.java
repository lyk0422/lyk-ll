package com.example.starter.carbon.web;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 清缴申请请求体。
 *
 * @param requestId       外部申请幂等键
 * @param enterpriseCode  企业编码
 * @param complianceYear  履约年度
 * @param allowanceAmount 申请以配额清缴数量（吨CO2e）
 * @param offsetAmount    申请以核证抵消信用抵减数量（吨CO2e）
 */
public record SurrenderRequest(
        @NotBlank String requestId,
        @NotBlank String enterpriseCode,
        @NotNull Integer complianceYear,
        @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal allowanceAmount,
        @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal offsetAmount) {
}
