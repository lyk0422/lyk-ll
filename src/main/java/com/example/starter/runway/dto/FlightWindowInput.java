package com.example.starter.runway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 合成航班占用窗输入：半开区间 [startAt, endAt)，UTC。
 *
 * @param flightNo 合成航班号（合法文本，允许包含任意分隔符）
 * @param runwayId 跑道编号
 * @param startAt  占用开始时刻（UTC，含）
 * @param endAt    占用结束时刻（UTC，不含）
 */
public record FlightWindowInput(
        @NotBlank String flightNo,
        @NotBlank String runwayId,
        @NotNull Instant startAt,
        @NotNull Instant endAt) {
}
