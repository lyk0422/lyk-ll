package com.example.starter.schedule.web.dto;

import java.util.List;

/**
 * 编排申请结果。replay=true 表示 requestId 重放，返回首次结果且未改动编排表。
 */
public record ScheduleResponseDto(
        String requestId,
        String outcome,
        Long entryId,
        Integer priority,
        boolean replay,
        List<ConflictItemDto> conflicts,
        List<PreemptionItemDto> preemptions) {
}
