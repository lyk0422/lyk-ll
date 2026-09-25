package com.example.starter.berth;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 靠泊窗口申请记录（对应表 berth_window）。
 *
 * @param id                 主键
 * @param requestId          幂等请求编号，全局唯一
 * @param fingerprint        幂等指纹：requestId 与全部业务字段规范化序列化的 SHA-256
 * @param vesselName         船名（合成数据）
 * @param berthId            泊位编号
 * @param startUtc           预计靠泊开始（UTC，半开区间起点，含）
 * @param endUtc             预计靠泊结束（UTC，半开区间终点，不含）
 * @param draftMeters        船舶吃水（米）
 * @param pilotsRequired     所需引航员数量，0 表示无需引航
 * @param status             窗口状态
 * @param replacesRequestId  改期来源请求编号；null 表示非改期申请
 * @param createdUtc         创建时刻（UTC）
 * @param updatedUtc         最近状态变更时刻（UTC）
 */
public record BerthWindow(
        Long id,
        String requestId,
        String fingerprint,
        String vesselName,
        String berthId,
        Instant startUtc,
        Instant endUtc,
        BigDecimal draftMeters,
        int pilotsRequired,
        WindowStatus status,
        String replacesRequestId,
        Instant createdUtc,
        Instant updatedUtc) {
}
