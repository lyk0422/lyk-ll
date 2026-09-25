package com.example.starter.bonded.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 单批核销中的一个商品项数量。
 *
 * @param hsCode 商品税号，必须是转关单中已核注的商品项
 * @param quantity 本批到货核销数量（件），必须为正整数
 */
public record VerifyItemRequest(
        @NotBlank(message = "税号不能为空")
        String hsCode,
        @NotNull @Positive(message = "核销数量必须为正整数")
        Long quantity) {
}
