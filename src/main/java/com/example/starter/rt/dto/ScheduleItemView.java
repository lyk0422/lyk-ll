package com.example.starter.rt.dto;

import java.time.OffsetDateTime;

/**
 * 排程项视图（窗口与实绩时刻均为 UTC）。
 *
 * @param itemId            排程项ID
 * @param fractionNo        分次号
 * @param windowStartUtc    窗口开始（UTC，含）
 * @param windowEndUtc      窗口结束（UTC，不含）
 * @param status            状态：SCHEDULED/CANCELLED/COMPLETED
 * @param deliveredDoseCgy  实绩投递剂量(cGy)，未完成为空
 * @param executedAtUtc     实绩执行时间（UTC），未完成为空
 */
public record ScheduleItemView(
        long itemId,
        int fractionNo,
        OffsetDateTime windowStartUtc,
        OffsetDateTime windowEndUtc,
        String status,
        Long deliveredDoseCgy,
        OffsetDateTime executedAtUtc) {
}
