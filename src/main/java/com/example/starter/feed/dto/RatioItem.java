package com.example.starter.feed.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 配方中的单项组分（原料或药物添加剂）及其质量占比。
 *
 * @param code  组分编码
 * @param ratio 添加比例（质量占比，0~1）；药物项不得超过字典最大添加比例，恰等于上限合法
 */
public record RatioItem(
        @NotBlank(message = "组分编码不能为空")
        String code,

        @NotNull(message = "添加比例不能为空")
        @DecimalMin(value = "0.000001", message = "添加比例必须为正数")
        @DecimalMax(value = "1.000000", message = "添加比例不得超过 1")
        BigDecimal ratio
) {
}
