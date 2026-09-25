package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 市电中断告警请求。告警按“基站＋停电起始时刻”去重合并。
 */
public record AlertRequest(
        @NotBlank String requestId,
        @NotBlank String stationCode,
        @NotNull Instant outageStartAt
) {
}
