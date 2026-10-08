package com.example.starter.web.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 交班响应视图：展示交班状态、双方签认与意见、版本号、实际责任分段以及接班预占。
 *
 * @param segments 受控计划的实际责任区间（切换前整段归出班员，切换后按交班时刻分段）
 * @param successorPreoccupied 接班引航员是否仍持有 [handoverAt, planEnd) 的接班预占
 * @param successorPreemptStart 接班预占起点（无预占为 null）
 * @param successorPreemptEnd 接班预占终点（无预占为 null）
 */
public record HandoverResponse(
        Long handoverId,
        Long planId,
        String status,
        Long fromPilotId,
        Long toPilotId,
        LocalDateTime handoverAt,
        boolean fromConfirmed,
        boolean toConfirmed,
        String fromComment,
        String toComment,
        long version,
        List<Segment> segments,
        boolean successorPreoccupied,
        LocalDateTime successorPreemptStart,
        LocalDateTime successorPreemptEnd) {

    /** 实际责任区间（半开区间 [start, end)）。 */
    public record Segment(
            Long pilotId,
            LocalDateTime start,
            LocalDateTime end) {
    }
}
