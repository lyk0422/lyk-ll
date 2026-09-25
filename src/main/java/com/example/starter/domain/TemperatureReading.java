package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 单元回水温度采集记录。
 *
 * @param id          主键
 * @param unitId      单元 ID
 * @param temperature 回水温度，单位 ℃
 * @param recordedAt  采集时间（UTC）
 * @param createdAt   入库时间（UTC）
 */
public record TemperatureReading(long id, long unitId, BigDecimal temperature,
                                 Instant recordedAt, Instant createdAt) {
}
