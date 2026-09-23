package com.example.starter.web.dto;

import com.example.starter.domain.SegmentStatus;

/**
 * 交接结果视图；同 requestId 同参重放时返回首次冻结结果。
 */
public record HandoverResponse(
        String shipmentId,
        String requestId,
        int closedVersion,
        SegmentStatus status,
        int currentVersion,
        String receiver,
        long handoverAt,
        long lastSequence,
        boolean replayed
) {
}
