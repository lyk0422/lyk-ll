package com.example.starter.model;

import java.time.Instant;

/**
 * 基站停电事件；同一事件期内重复告警合并为一个事件。
 *
 * @param id              主键
 * @param stationCode     基站编码
 * @param outageStartTime 停电起始时刻，UTC
 * @param status          事件状态：ACTIVE 停电中 / RESOLVED 已恢复
 * @param resolvedAt      市电恢复时刻，UTC；未恢复为 null
 * @param createdAt       创建时间，UTC
 */
public record OutageEvent(long id, String stationCode, Instant outageStartTime, OutageStatus status,
                          Instant resolvedAt, Instant createdAt) {
}
