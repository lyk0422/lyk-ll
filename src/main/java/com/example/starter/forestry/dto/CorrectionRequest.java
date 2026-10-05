package com.example.starter.forestry.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 蓄积设计值补正请求（以 requestId 幂等）。仅 CORRECTION_REQUIRED 状态的申请可补正一次；
 * 补正后按合成蓄积表再复核，仍不一致按撤回处理，不生成证照。
 *
 * @param requestId      幂等键
 * @param treeCount      补正后的申请采伐株数，单位株
 * @param designedVolume 补正后的小班蓄积设计值，单位立方米
 */
public record CorrectionRequest(
        @NotBlank String requestId,
        @NotNull @Positive Integer treeCount,
        @NotNull @Positive BigDecimal designedVolume
) {
}
