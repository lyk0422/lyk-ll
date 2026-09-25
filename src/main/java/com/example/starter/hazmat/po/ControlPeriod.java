package com.example.starter.hazmat.po;

import java.time.LocalDateTime;

/**
 * 管制时段（半开区间 [startTime, endTime)）。
 *
 * @param id        主键
 * @param routeCode 受限路线编码
 * @param startTime 管制起始时刻（计划通过时刻 == 起始时刻视为受管制）
 * @param endTime   管制结束时刻（计划通过时刻 == 结束时刻视为不受管制）
 */
public record ControlPeriod(long id, String routeCode, LocalDateTime startTime, LocalDateTime endTime) {
}
