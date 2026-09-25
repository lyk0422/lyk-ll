package com.example.starter.gas.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 调整凭证请求：封账后差错经双方签认（signOffA/signOffB 均为 true）处理，
 * 调整凭证引用原凭证号，提交即按双方签认生效。
 *
 * @param volume          修正后的交接体积合计（体积单位）
 * @param heatingValue    修正后的加权平均热值（能量单位/体积单位）
 * @param signOffA        A 方签认标记
 * @param signOffB        B 方签认标记
 */
public record AdjustmentRequest(
        @NotBlank String requestId,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal volume,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal heatingValue,
        @NotNull Boolean signOffA,
        @NotNull Boolean signOffB) {
}
