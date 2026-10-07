package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 分段回执单机功率项：功率须等于冻结的该分钟阶梯值。
 */
public record StagedReceiptItem(
        @NotBlank String turbineCode,
        @PositiveOrZero int powerKw) {
}
