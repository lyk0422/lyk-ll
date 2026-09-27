package com.example.starter.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 受控排缸请求：缸次在投料前须取得效价补偿批准。
 * 同样引用已发布冻结配方版本；compensated=true 的缸次投料走批次余量原子扣减与补偿目标偏差校验。
 */
public record ScheduleControlledRequest(
        @NotBlank String requestId,
        @NotBlank String lotNo,
        @NotBlank String vatNo,
        @NotBlank String recipeCode,
        @NotNull @Min(1) Integer recipeVersion,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal fabricWeightKg) {
}
