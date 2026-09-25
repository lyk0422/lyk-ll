package com.example.starter.feed.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.List;

/**
 * 配方提交请求；每次提交生成一个独立新版本，新版本独立做合规校验。
 *
 * @param requestId    幂等键，非空
 * @param formulaCode  配方编码（同编码下版本递增）
 * @param targetSpecies 目标动物种类；含药配方必填且须与投产批次物种一致
 * @param items        配方组成：原料/药物添加剂编码 + 添加比例（0~1 之间的小数，恰等于目录上限合法）
 */
public record SubmitFormulaRequest(
        @NotBlank String requestId,
        @NotBlank String formulaCode,
        String targetSpecies,
        @NotNull List<Item> items) {

    /**
     * 配方组成项。
     *
     * @param ingredientCode 原料或药物添加剂目录编码
     * @param ratio          添加比例（占全价饲料比例，如 0.002 表示 0.2%）
     */
    public record Item(@NotBlank String ingredientCode, @NotNull @PositiveOrZero BigDecimal ratio) {
    }
}
