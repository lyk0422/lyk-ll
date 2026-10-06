package com.example.starter.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 洞外亮度检测请求。
 *
 * @param requestId  幂等键
 * @param brightness 洞外亮度合成值，单位 lux；低于回路阈值升一档，恰等不触发
 */
public record BrightnessRequest(
        @NotBlank String requestId,
        @NotNull @Min(0) Integer brightness) {
}
