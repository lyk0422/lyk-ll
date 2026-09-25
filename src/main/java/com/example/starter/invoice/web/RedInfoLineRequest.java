package com.example.starter.invoice.web;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 部分红冲行项目请求。
 *
 * @param originalLineNo 原蓝票行号，从 1 开始
 * @param amount         本行红冲金额，单位：元，须为正且不超过该行可红冲余额
 */
public record RedInfoLineRequest(
        @NotNull @Positive Integer originalLineNo,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount) {
}
