package com.example.starter.transition.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 登记合成品种及其新品种工艺窗口（车速 m/min、上料浓度 %）与正品门槛（抗张指数 N·m/g）。
 *
 * @param requestId      幂等键
 * @param gradeCode      品种编码
 * @param basisWeight    克重 g/m²
 * @param ashLevel       灰分等级
 * @param speedMin       车速窗口下限，边界含
 * @param speedMax       车速窗口上限，边界含
 * @param consistencyMin 上料浓度窗口下限 %，边界含
 * @param consistencyMax 上料浓度窗口上限 %，边界含
 * @param tensileMin     抗张指数正品门槛 N·m/g，恰达即合格
 */
public record GradeUpsertRequest(
        @NotBlank String requestId,
        @NotBlank String gradeCode,
        @NotNull @DecimalMin("0.0") BigDecimal basisWeight,
        @NotNull Integer ashLevel,
        @NotNull @DecimalMin("0.0") BigDecimal speedMin,
        @NotNull @DecimalMin("0.0") BigDecimal speedMax,
        @NotNull @DecimalMin("0.0") BigDecimal consistencyMin,
        @NotNull @DecimalMin("0.0") BigDecimal consistencyMax,
        @NotNull @DecimalMin("0.0") BigDecimal tensileMin
) {
}
