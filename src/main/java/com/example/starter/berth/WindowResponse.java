package com.example.starter.berth;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 靠泊窗口响应。
 *
 * @param requestId          幂等请求编号
 * @param vesselName         船名
 * @param berthId            泊位编号
 * @param startUtc           预计靠泊开始（UTC）
 * @param endUtc             预计靠泊结束（UTC）
 * @param draftMeters        船舶吃水（米）
 * @param pilotsRequired     所需引航员数量
 * @param status             窗口状态
 * @param assignedPilots     已分配引航员编号（审批通过后非空）
 * @param replacesRequestId  改期来源请求编号；null 表示非改期申请
 * @param createdUtc         创建时刻（UTC）
 * @param updatedUtc         最近状态变更时刻（UTC）
 */
public record WindowResponse(
        String requestId,
        String vesselName,
        String berthId,
        Instant startUtc,
        Instant endUtc,
        BigDecimal draftMeters,
        int pilotsRequired,
        WindowStatus status,
        List<String> assignedPilots,
        String replacesRequestId,
        Instant createdUtc,
        Instant updatedUtc) {
}
