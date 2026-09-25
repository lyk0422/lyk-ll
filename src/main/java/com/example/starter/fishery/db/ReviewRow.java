package com.example.starter.fishery.db;

import com.example.starter.fishery.domain.ReviewConclusion;
import com.example.starter.fishery.domain.ReviewReason;
import com.example.starter.fishery.domain.ReviewStatus;

import java.time.Instant;

/**
 * 人工复核案件行；复核结论为最终口径。
 */
public record ReviewRow(
        Long id,
        String tripCode,
        String vesselCode,
        ReviewReason reason,
        ReviewStatus status,
        ReviewConclusion conclusion,
        String note,
        Instant createdAt,
        Instant decidedAt) {
}
