package com.example.starter.greenhouse.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 策略“下发”请求：requestId 幂等键，同一分区相邻两次生效间隔不得少于 30 分钟。
 */
public record StrategyActivateRequest(
        @NotBlank(message = "不能为空") String strategyCode,
        @NotBlank(message = "不能为空") String requestId) {
}
