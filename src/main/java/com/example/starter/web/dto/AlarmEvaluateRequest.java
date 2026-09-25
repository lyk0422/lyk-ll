package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * 预警分级评估（幂等）。以 triggerTime 时刻复算最近 1 小时/24 小时速率：
 * 日速率严格大于报警值→红色（立即停产撤人）；否则小时速率严格大于预警值→黄色；恰等阈值判正常。
 */
public record AlarmEvaluateRequest(
        @NotBlank String requestId,
        @NotBlank String zoneCode,
        @NotNull LocalDateTime triggerTime) {
}
