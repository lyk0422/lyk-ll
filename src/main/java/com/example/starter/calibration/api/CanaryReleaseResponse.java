package com.example.starter.calibration.api;

import com.example.starter.calibration.domain.CanaryStatus;

import java.time.Instant;
import java.util.List;

/**
 * 灰度发布批次视图。
 */
public record CanaryReleaseResponse(
        long id,
        String releaseId,
        String paramKey,
        long canaryVersionId,
        CanaryStatus status,
        List<String> vehicleIds,
        String createdBy,
        Instant createdAt,
        String promotedBy,
        Instant promotedAt,
        String rolledBackBy,
        Instant rolledBackAt) {
}
