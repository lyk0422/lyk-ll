package com.example.starter.transition.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 过渡纸卷合成检验数据提交；首次判定强制降级，仅记录是否达到正品门槛。
 *
 * @param requestId           幂等键
 * @param rollNo              卷号
 * @param measuredBasisWeight 实测克重 g/m²
 * @param measuredAshLevel    实测灰分等级
 * @param measuredTensile     实测抗张指数 N·m/g
 */
public record InspectionRequest(
        @NotBlank String requestId,
        @NotBlank String rollNo,
        @NotNull @DecimalMin("0.0") BigDecimal measuredBasisWeight,
        @NotNull Integer measuredAshLevel,
        @NotNull @DecimalMin("0.0") BigDecimal measuredTensile
) {
}
