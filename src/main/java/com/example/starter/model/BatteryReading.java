package com.example.starter.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 电池容量读数，按“基站＋抄表时刻”去重，乱序到达按抄表时刻归位。
 *
 * @param id               主键
 * @param stationCode      基站编码
 * @param readingTime      抄表时刻，UTC
 * @param remainingPercent 剩余容量百分比，0-100
 * @param requestId        上报请求幂等键
 * @param createdAt        入库时间，UTC
 */
public record BatteryReading(long id, String stationCode, Instant readingTime,
                             BigDecimal remainingPercent, String requestId, Instant createdAt) {
}
