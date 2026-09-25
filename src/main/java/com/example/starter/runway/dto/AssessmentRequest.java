package com.example.starter.runway.dto;

import com.example.starter.runway.AssessmentType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 巡检评估请求。requestId 为幂等标识。
 *
 * @param requestId          幂等请求标识
 * @param frictionCoefficient 道面摩擦系数：低于0.35须持续关闭，恰0.35合格
 * @param assessmentType     CLEAR_TO_REOPEN=清除即可恢复 / REPAIR_REQUIRED=修复后恢复
 * @param assessedAt         评估时间（UTC）
 */
public record AssessmentRequest(
        @NotBlank String requestId,
        @NotNull @DecimalMin("0.00") BigDecimal frictionCoefficient,
        @NotNull AssessmentType assessmentType,
        @NotNull Instant assessedAt) {
}
