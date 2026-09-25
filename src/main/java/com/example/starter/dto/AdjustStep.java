package com.example.starter.dto;

import com.example.starter.domain.ValveAction;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 单个阀门步进指令。
 *
 * @param unitCode 被调节单元编码
 * @param action   调节方向：欠流 OPEN 开大，过流 CLOSE 关小
 * @param delta    步进幅度（开度百分点），区间 (0,10]，恰 10% 合法
 */
public record AdjustStep(
        @NotBlank String unitCode,
        @NotNull ValveAction action,
        @NotNull
        @DecimalMin(value = "0.001", message = "步进幅度必须大于0")
        @DecimalMax(value = "10.000", inclusive = true, message = "单次调整幅度不得超过10%")
        BigDecimal delta
) {
}
