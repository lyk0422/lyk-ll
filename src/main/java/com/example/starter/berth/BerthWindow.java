package com.example.starter.berth;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 靠泊窗口申请记录。时间均为 UTC；[startTime, endTime) 为半开区间。
 *
 * @param id             主键
 * @param requestId      幂等键
 * @param fingerprint    幂等指纹（requestId 与全部业务字段的无歧义序列化）
 * @param vesselName     船名
 * @param berthId        泊位ID
 * @param startTime      预计靠泊起始时刻（UTC，含）
 * @param endTime        预计靠泊结束时刻（UTC，不含）
 * @param draft          吃水，单位米
 * @param pilotsRequired 所需引航员数
 * @param status         当前状态
 * @param createdAt      创建时刻（UTC）
 * @param updatedAt      最近变更时刻（UTC）
 */
public record BerthWindow(
        Long id,
        String requestId,
        String fingerprint,
        String vesselName,
        long berthId,
        Instant startTime,
        Instant endTime,
        BigDecimal draft,
        int pilotsRequired,
        BerthWindowStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
