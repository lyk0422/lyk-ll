package com.example.starter.model;

import java.time.Instant;

/**
 * 风速监测数据行记录。
 *
 * @param id         记录 id
 * @param lineId     线路 id
 * @param stationId  站点 id
 * @param measuredAt 测量时刻（UTC）
 * @param speedMs    风速，单位 m/s
 * @param late       是否迟到数据
 */
public record WindReading(long id, long lineId, long stationId, Instant measuredAt, double speedMs, boolean late) {
}
