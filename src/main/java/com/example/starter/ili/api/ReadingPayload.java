package com.example.starter.ili.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 单条缺陷读数载荷。
 *
 * @param readingKey 读数键，批次+回合内唯一
 * @param stationM   绝对里程，单位米，非负
 * @param depthPct   深度，占壁厚百分比，非负
 */
public record ReadingPayload(
        @NotBlank String readingKey,
        @NotNull @DecimalMin("0") BigDecimal stationM,
        @NotNull @DecimalMin("0") BigDecimal depthPct) {
}
