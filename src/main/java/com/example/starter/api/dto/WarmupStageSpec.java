package com.example.starter.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 升温曲线阶段定义（合成曲线的一段）。
 *
 * @param targetTemp         目标温度，摄氏度，须为正数
 * @param maxDurationMinutes 最长用时，分钟，恰满未达目标自动转保温段
 */
public record WarmupStageSpec(
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal targetTemp,
        @NotNull @Min(1) Integer maxDurationMinutes) {
}
