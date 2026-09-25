package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 归档后迟到温度事件，仅记录不参与平衡计算。
 *
 * @param id          主键
 * @param unitId      单元 ID
 * @param taskId      关联的已归档任务 ID，可为 null
 * @param temperature 迟到的回水温度，单位 ℃
 * @param recordedAt  采集时间（UTC），早于归档时间
 * @param createdAt   事件登记时间（UTC）
 */
public record LateTemperatureEvent(long id, long unitId, Long taskId, BigDecimal temperature,
                                   Instant recordedAt, Instant createdAt) {
}
