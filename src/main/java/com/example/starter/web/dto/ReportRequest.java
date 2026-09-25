package com.example.starter.web.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 执行回报请求。reportTime 为采集时刻，乱序回报按此归位。
 */
public record ReportRequest(
        @NotBlank String requestId,
        @NotNull Long commandId,
        @NotNull Long turbineId,
        @NotNull LocalDateTime reportTime,
        @NotNull @PositiveOrZero Integer actualKw) {
}
