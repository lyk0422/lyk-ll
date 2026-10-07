package com.example.starter.runway.dto;

import java.time.Instant;

/**
 * 施工段视图。
 *
 * @param segmentNo       段号
 * @param startMeters     段起点（单位：米，含）
 * @param endMeters       段终点（单位：米，不含）
 * @param status          段状态：LOCKED闭锁中/COMPLETED验收完成
 * @param completedAt     段验收完成时刻（UTC），未完成为 null
 * @param acceptanceCount 已登记验收凭据数（不含迟到登记）
 */
public record SegmentView(
        int segmentNo,
        int startMeters,
        int endMeters,
        String status,
        Instant completedAt,
        int acceptanceCount) {
}
