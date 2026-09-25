package com.example.starter.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;

/**
 * 风压上报请求。
 *
 * @param requestId   幂等键
 * @param pressureKpa 制动管风压(kPa)
 * @param occurredAt  采集时间(本地时区); 为空时取服务器当前时间
 */
public record PressureRequest(
        @NotBlank String requestId,
        @Min(0) int pressureKpa,
        LocalDateTime occurredAt) {
}
