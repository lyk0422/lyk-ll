package com.example.starter.web.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 调整凭证请求：封账后发现差错，经双方签认后以调整凭证处理并引用原凭证号。
 *
 * @param requestId 幂等键
 * @param originalVoucherNo 原凭证号（必须已封账）
 * @param partyAConfirmer 交出方签认人
 * @param partyBConfirmer 接收方签认人
 * @param volume 调整后的交接体积，单位 Nm3
 * @param avgHeatingValue 调整后的加权平均热值，单位 MJ/Nm3
 * @param reason 差错与调整说明
 */
public record AdjustmentRequest(
        @NotBlank String requestId,
        @NotBlank String originalVoucherNo,
        @NotBlank String partyAConfirmer,
        @NotBlank String partyBConfirmer,
        @NotNull BigDecimal volume,
        @NotNull BigDecimal avgHeatingValue,
        @NotBlank @Size(max = 500) String reason) {
}
