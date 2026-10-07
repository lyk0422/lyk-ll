package com.example.starter.rolling.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * 到期轧辊返修放行申请。
 *
 * <p>只有“达到寿命上限且已冻结”的物理辊当前代次可申请；磨后直径保留两位小数，
 * 不少于 500 毫米且不得大于上一代直径；新周期公里上限为正且不超过上一代上限；
 * 拟用新辊号不得占用任何已有辊号。</p>
 *
 * @param requestId        幂等键，指纹覆盖该键与全部业务字段
 * @param oldRollNo        旧代辊号（寿命到期、已冻结的当前代次）
 * @param groundDiameterMm 磨后直径（毫米，两位小数，&gt;=500，不得大于上一代直径）
 * @param newRollNo        拟用新辊号（不得占用已有号，跨物理辊引用拒绝）
 * @param newCycleLimitKm  新一代周期公里上限（公里，正数且不超过上一代上限）
 * @param inspectionCert   检验凭据
 * @param expectedVersion  提交时物理辊聚合版本（从 1 起；成功放行时校验，旧版本 409）
 */
public record RepairApplyRequest(
        @NotBlank String requestId,
        @NotBlank String oldRollNo,
        @NotNull @DecimalMin("500.00") @Digits(integer = 6, fraction = 2) BigDecimal groundDiameterMm,
        @NotBlank String newRollNo,
        @NotNull @Positive BigDecimal newCycleLimitKm,
        @NotBlank String inspectionCert,
        @NotNull @Positive Integer expectedVersion) {
}
