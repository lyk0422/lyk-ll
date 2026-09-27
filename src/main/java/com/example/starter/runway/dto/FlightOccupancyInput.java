package com.example.starter.runway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 合成航班占用窗输入：UTC 半开区间 [occupyStart, occupyEnd)。
 *
 * @param runwayId    跑道编号（合成数据）
 * @param flightNo    合成航班号（合法文本可含任意分隔符）
 * @param occupyStart 占用开始时刻（UTC，含）
 * @param occupyEnd   占用结束时刻（UTC，不含），必须严格晚于开始时刻
 */
public record FlightOccupancyInput(
        @NotBlank String runwayId,
        @NotBlank String flightNo,
        @NotNull Instant occupyStart,
        @NotNull Instant occupyEnd) {
}
