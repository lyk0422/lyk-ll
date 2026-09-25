package com.example.starter.bonded.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * 转关申请商品项（税号、数量、单价）。
 *
 * @param hsCode 商品税号
 * @param quantity 数量（件），必须为正整数
 * @param unitPrice 单价（元），必须大于 0
 */
public record ItemRequest(
        @NotBlank(message = "税号不能为空")
        String hsCode,
        @NotNull @Positive(message = "数量必须为正整数")
        Long quantity,
        @NotNull @DecimalMin(value = "0.0001", message = "单价必须大于 0")
        BigDecimal unitPrice) {
}
