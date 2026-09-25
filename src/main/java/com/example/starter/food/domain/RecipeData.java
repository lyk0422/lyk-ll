package com.example.starter.food.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * 合规评估使用的配方数据（提交数据或批准快照均可还原为该结构复算）。
 */
public record RecipeData(
        String categoryCode,
        List<DirectAdditive> additives,
        List<Ingredient> ingredients) {

    /** 直接加入的添加剂，amount 单位 g/kg（以终产品质量计）。 */
    public record DirectAdditive(String additiveCode, BigDecimal amount) {
    }

    /** 复合配料，proportion 为占终产品质量比例（0~1）。 */
    public record Ingredient(String compoundCode, BigDecimal proportion) {
    }
}
