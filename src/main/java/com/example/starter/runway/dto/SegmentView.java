package com.example.starter.runway.dto;

import java.time.Instant;

/**
 * 施工段视图。
 *
 * @param segmentId      施工段主键
 * @param segmentNo      段号
 * @param startMeter     区间起点（单位：米，含）
 * @param endMeter       区间终点（单位：米，不含）
 * @param status         状态：PENDING/LOCKED/CLEARED
 * @param clearedAt      验收解除闭锁时刻（UTC），未解除为null
 * @param clearanceCount 正常验收凭据的不同验收人数（迟到留痕不计入）
 */
public record SegmentView(
        long segmentId,
        int segmentNo,
        int startMeter,
        int endMeter,
        String status,
        Instant clearedAt,
        int clearanceCount) {
}
