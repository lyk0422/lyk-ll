package com.example.starter.runway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 合成航班占用窗输入（半开 [windowStart, windowEnd)，UTC）。
 *
 * @param runwayId    跑道编号（合成数据；合法文本可含分隔符）
 * @param windowStart 占用开始时刻（UTC，含）
 * @param windowEnd   占用结束时刻（UTC，不含）
 */
public record FlightWindowInput(
        @NotBlank String runwayId,
        @NotNull Instant windowStart,
        @NotNull Instant windowEnd) {
}
