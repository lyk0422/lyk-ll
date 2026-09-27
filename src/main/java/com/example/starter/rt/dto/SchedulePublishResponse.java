package com.example.starter.rt.dto;

import java.util.List;

/**
 * 批量排程发布结果。整批原子：成功时全部条目为 BOOKED；任一冲突整批零占用（返回错误状态）。
 *
 * @param requestId 发布幂等键
 * @param replayed  是否为同键同参重放
 * @param schedules 本次冻结的排程条目，按请求顺序返回
 */
public record SchedulePublishResponse(
        String requestId,
        boolean replayed,
        List<ScheduleResponse> schedules) {
}
