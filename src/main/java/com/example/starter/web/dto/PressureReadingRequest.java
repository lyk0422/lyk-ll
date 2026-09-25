package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalDateTime;

/**
 * 风压读数上报请求。
 *
 * @param requestId  幂等请求键
 * @param pressure   列车管风压，单位 kPa
 * @param recordedAt 采集时间（上报方时钟，JVM 时区解释）
 */
public record PressureReadingRequest(
        @NotBlank String requestId,
        @NotNull @PositiveOrZero Integer pressure,
        @NotNull LocalDateTime recordedAt) {
}
