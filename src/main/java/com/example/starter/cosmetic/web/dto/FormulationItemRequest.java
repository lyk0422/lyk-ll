package com.example.starter.cosmetic.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * 配方成分请求条目。
 *
 * @param code             成分编码
 * @param name             成分名称
 * @param concentrationPct 质量百分比浓度（%），不得为负
 */
public record FormulationItemRequest(
        @NotBlank String code,
        @NotBlank String name,
        @NotNull @PositiveOrZero BigDecimal concentrationPct) {
}
