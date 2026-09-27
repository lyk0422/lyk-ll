package com.example.starter.runway.dto;

import java.time.Instant;

/**
 * 例行计划检查项视图。
 *
 * @param itemCode      检查项编码
 * @param seqNo         稳定序号
 * @param completedAt   完成时刻（UTC），未回报为 null
 * @param recordedLate  是否中止后迟到回报
 */
public record CheckItemView(
        String itemCode,
        int seqNo,
        Instant completedAt,
        boolean recordedLate) {
}
