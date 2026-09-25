package com.example.starter.fishery.api.dto;

import com.example.starter.fishery.domain.ReviewConclusion;
import com.example.starter.fishery.domain.ReviewReason;
import com.example.starter.fishery.domain.ReviewStatus;

import java.time.Instant;

/**
 * 复核案件视图。
 */
public record ReviewCaseView(
        Long id,
        ReviewReason reason,
        ReviewStatus status,
        ReviewConclusion conclusion,
        String note,
        Instant createdAt,
        Instant decidedAt) {
}
