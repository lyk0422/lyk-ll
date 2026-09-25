package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 调度单生成请求。基于最新抄表估算：可支撑时长低于 120 分钟才派单，恰为 120 分钟不派。
 * genCode 省略时自动选择一台空闲油机。
 */
public record DispatchRequest(
        @NotBlank String requestId,
        @NotBlank String stationCode,
        @NotNull Instant outageStartAt,
        String genCode
) {
}
