package com.example.starter.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 升温阶段确认请求（以 requestId 幂等）。阶段确认须两名不同操作人先后复核，
 * 单人确认不生效；确认时实测温度须达到阶段目标温度。
 *
 * @param requestId  确认幂等键
 * @param operatorId 操作人编号
 * @param actualTemp 实测温度，摄氏度，须大于等于阶段目标温度
 */
public record WarmupConfirmRequest(
        @NotBlank String requestId,
        @NotBlank String operatorId,
        @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal actualTemp) {
}
