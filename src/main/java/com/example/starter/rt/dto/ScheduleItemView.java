package com.example.starter.rt.dto;

import java.time.Instant;

/**
 * 排程明细视图。
 *
 * @param itemId            排程明细ID
 * @param fractionNo        分次号
 * @param startAt           窗口开始（UTC，含起点）
 * @param endAt             窗口结束（UTC，不含终点）
 * @param status            明细状态：ACTIVE/COMPLETED/CANCELLED
 * @param deliveredDoseCgy  实绩投递剂量(cGy)，未执行为空
 * @param executedAt        实绩执行时间（UTC），未执行为空
 */
public record ScheduleItemView(
        Long itemId,
        Integer fractionNo,
        Instant startAt,
        Instant endAt,
        String status,
        Long deliveredDoseCgy,
        Instant executedAt) {
}
