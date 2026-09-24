package com.example.starter.berth.dto;

import com.example.starter.berth.domain.WindowStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 靠泊窗口响应。
 *
 * @param id             窗口 id
 * @param requestId      幂等请求键
 * @param vesselName     船名
 * @param berthCode      泊位代码
 * @param startAt        预计靠泊开始时刻（UTC）
 * @param endAt          预计靠泊结束时刻（UTC）
 * @param draftMeters    吃水（米）
 * @param pilotsRequired 所需引航员人数
 * @param assignedPilots 已分配的引航员代码列表（终态释放后为空）
 * @param status         窗口状态
 * @param supersedesId   改期取代的原窗口 id；非改期申请为 null
 * @param createdAt      创建时间（UTC）
 * @param updatedAt      最后更新时间（UTC）
 */
public record WindowResponse(
        long id,
        String requestId,
        String vesselName,
        String berthCode,
        Instant startAt,
        Instant endAt,
        BigDecimal draftMeters,
        int pilotsRequired,
        List<String> assignedPilots,
        WindowStatus status,
        Long supersedesId,
        Instant createdAt,
        Instant updatedAt) {
}
