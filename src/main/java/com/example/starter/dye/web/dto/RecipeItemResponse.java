package com.example.starter.dye.web.dto;

import java.math.BigDecimal;

/**
 * 配方条目视图。
 */
public record RecipeItemResponse(
        String dyestuffCode,
        BigDecimal gramsPerLiter) {
}
