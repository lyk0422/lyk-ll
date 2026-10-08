package com.example.starter.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 分缸目标子缸：占用空闲染缸 vatNo，生成子缸 lotNo，分摊布重 fabricWeightKg。
 * 提交顺序即子缸编号（seq 从 1 起），最小耗用余量按编号升序循环分配，故顺序是业务参数。
 * 重量为正数公斤，最多三位小数。
 */
public record SplitLotTargetInput(
        @NotBlank String lotNo,
        @NotBlank String vatNo,
        @NotNull @DecimalMin(value = "0", inclusive = false)
        @Digits(integer = 9, fraction = 3) BigDecimal fabricWeightKg) {
}
