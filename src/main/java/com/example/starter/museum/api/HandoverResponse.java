package com.example.starter.museum.api;

import com.example.starter.museum.domain.HandoverType;

import java.time.Instant;

/** 点交单视图：completed=true 表示双方均已确认、点交生效。 */
public record HandoverResponse(
        String contractNo,
        HandoverType type,
        boolean lenderConfirmed,
        boolean borrowerConfirmed,
        boolean completed,
        Instant completedAt
) {
}
