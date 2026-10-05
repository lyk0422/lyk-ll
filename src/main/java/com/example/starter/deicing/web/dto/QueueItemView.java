package com.example.starter.deicing.web.dto;

import com.example.starter.deicing.domain.DeicingType;
import com.example.starter.deicing.domain.RequestStatus;
import java.time.Instant;

/**
 * 队列记录视图，所有时刻均为 UTC。
 *
 * @param earliestReassignTime HOT 风险时给出的最早可重排（重新出队）时刻，否则为空
 */
public record QueueItemView(
        String requestId,
        long sequence,
        String flightNumber,
        String standCode,
        DeicingType type,
        int requiredBays,
        int durationMinutes,
        Instant targetTakeoffTime,
        RequestStatus status,
        Instant deadline,
        Instant scheduledStart,
        Instant scheduledEnd,
        Instant actualStart,
        Instant actualEnd,
        Integer hotMinutes,
        String hotRiskReason,
        Instant earliestReassignTime,
        Instant actualTakeoffTime,
        Instant createdAt,
        Instant updatedAt) {
}
