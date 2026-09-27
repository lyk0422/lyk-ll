package com.example.starter.rt.dto;

import java.time.OffsetDateTime;

/**
 * 排程占用项视图（窗口与实绩时刻均为 UTC）。
 *
 * @param itemId           排程项ID
 * @param fractionNo       分次号
 * @param windowStartUtc   占用窗口起点（UTC，含）
 * @param windowEndUtc     占用窗口终点（UTC，不含）
 * @param status           状态：SCHEDULED/COMPLETED/CANCELLED
 * @param executedAtUtc    实绩执行时间（UTC），未完成为空
 * @param deliveredDoseCgy 实绩投递剂量(cGy)，未完成为空
 * @param cancelReason     取消原因（MANUAL/REVISION），未取消为空
 */
public record ScheduleItemView(
        Long itemId,
        Integer fractionNo,
        OffsetDateTime windowStartUtc,
        OffsetDateTime windowEndUtc,
        String status,
        OffsetDateTime executedAtUtc,
        Long deliveredDoseCgy,
        String cancelReason) {
}
