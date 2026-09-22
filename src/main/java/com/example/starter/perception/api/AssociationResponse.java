package com.example.starter.perception.api;

import java.time.Instant;
import java.util.List;

/**
 * 一批检测关联的整体响应。
 */
public record AssociationResponse(
        List<DetectionResult> results,
        int acceptedCount,
        int rejectedCount,
        Instant processedAt
) {
}
