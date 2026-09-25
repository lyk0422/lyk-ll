package com.example.starter.fishery.api.dto;

import com.example.starter.fishery.domain.TripStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 进港报告受理结果；{@code deviationPct} 为分鱼种最大偏差百分比，
 * {@code reviewTriggered=true} 表示偏差超过 3% 已转人工复核。
 */
public record LandingResponse(
        String tripCode,
        TripStatus status,
        Instant landedAt,
        BigDecimal deviationPct,
        boolean reviewTriggered,
        List<WeightLineView> landedLines,
        List<WeightLineView> loggedLines,
        boolean replayed) implements ReplayAware {

    @Override
    public LandingResponse withReplayed(boolean replayed) {
        return new LandingResponse(tripCode, status, landedAt, deviationPct, reviewTriggered,
                landedLines, loggedLines, replayed);
    }
}
