package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;

/**
 * 风速数据上报请求。
 *
 * @param requestId  幂等请求标识
 * @param stationId  采集站点 id
 * @param measuredAt 测量时刻（UTC）
 * @param speedMs    风速，单位 m/s
 */
public record WindReadingRequest(
        @NotBlank String requestId,
        @NotNull Long stationId,
        @NotNull Instant measuredAt,
        @NotNull @PositiveOrZero Double speedMs) {
}
