package com.example.starter.legality.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 延误延长补交请求。
 *
 * @param requestId    对应值勤记录的幂等请求ID
 * @param delayMinutes 本次延误延长分钟数，必须为正
 */
public record DelayRequest(
        @NotBlank String requestId,
        @NotNull @Positive Integer delayMinutes) {
}
