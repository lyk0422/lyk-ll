package com.example.starter.slot.dto;

/**
 * 单个（时刻，周）占用结果。
 *
 * @param airport  机场三字码
 * @param slotTime 时刻 HH:mm
 * @param week     航季内周序号
 * @param basis    占用依据 RETAINED / POOL
 */
public record AllocationView(
        String airport,
        String slotTime,
        Integer week,
        String basis) {
}
