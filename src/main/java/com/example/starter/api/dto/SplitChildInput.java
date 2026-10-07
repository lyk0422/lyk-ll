package com.example.starter.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 分缸子缸输入：目标缸次号、目标空闲染缸与正公斤数（最多三位小数，
 * 各子缸合计须精确等于来源布重，否则整单拒绝）。
 */
public record SplitChildInput(
        @NotBlank String lotNo,
        @NotBlank String vatNo,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal fabricWeightKg) {
}
