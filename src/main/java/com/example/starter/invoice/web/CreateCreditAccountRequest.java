package com.example.starter.invoice.web;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建授信账户请求（合成数据入口）。
 *
 * @param sellerTaxNo 销方纳税人识别号
 * @param totalLimit  授信总额度，单位：元，须为正
 */
public record CreateCreditAccountRequest(
        @NotBlank @Size(max = 32) String sellerTaxNo,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal totalLimit) {
}
