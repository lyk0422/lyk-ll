package com.example.starter.schedule.domain;

import java.time.Instant;

/**
 * 过境预报窗口（合成数据），半开区间 [start, end)，端点相接不算冲突。
 *
 * @param id 主键，空值表示未持久化
 * @param satelliteId 卫星主键
 * @param stationId 地面站主键
 * @param start 窗口开始时刻（含端点，UTC）
 * @param end 窗口结束时刻（不含端点，UTC）
 * @param dayKey 窗口起始时刻对应的 UTC 日期 yyyy-MM-dd，编排按日发布
 */
public record ContactWindow(Long id, Long satelliteId, Long stationId,
                            Instant start, Instant end, String dayKey) {
}
