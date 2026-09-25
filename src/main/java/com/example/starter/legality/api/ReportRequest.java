package com.example.starter.legality.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

/**
 * 单条机组值勤报到请求。时刻建议带偏移量（如 {@code 2026-03-10T06:00:00Z}），统一折算 UTC。
 *
 * @param requestId        幂等请求ID；指纹覆盖 requestId、机组成员、航班与报到时刻
 * @param batchId          可选，预归属的校验批次ID
 * @param crewId           机组成员ID
 * @param flightNo         航班号
 * @param reportTime       报到时刻
 * @param scheduledEndTime 计划执勤结束时刻，不得早于报到时刻
 */
public record ReportRequest(
        @NotBlank String requestId,
        String batchId,
        @NotBlank String crewId,
        @NotBlank String flightNo,
        @NotNull OffsetDateTime reportTime,
        @NotNull OffsetDateTime scheduledEndTime) {
}
