package com.example.starter.dispatch.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 调度令中单个闸门的执行目标。
 *
 * @param gateCode       闸门编码
 * @param targetOpening  目标开度，百分比 0~100，单位 %
 */
public record GateTarget(
        @NotBlank String gateCode,
        @NotNull BigDecimal targetOpening) {
}
