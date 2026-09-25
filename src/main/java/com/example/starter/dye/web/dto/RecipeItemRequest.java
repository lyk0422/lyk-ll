package com.example.starter.dye.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 配方条目：某染化料的小样浓度。
 *
 * @param gramsPerLiter 小样浓度，单位克/升
 */
public record RecipeItemRequest(
        @NotBlank String dyestuffCode,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal gramsPerLiter) {
}
