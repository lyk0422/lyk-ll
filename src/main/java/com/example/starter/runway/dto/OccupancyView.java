package com.example.starter.runway.dto;

import java.time.Instant;

/**
 * 冲突证据中引用的一个占用窗视图（半开，UTC）。
 *
 * @param windowStart 占用开始时刻（含）
 * @param windowEnd   占用结束时刻（不含）
 */
public record OccupancyView(Instant windowStart, Instant windowEnd) {
}
