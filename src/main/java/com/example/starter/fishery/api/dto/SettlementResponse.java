package com.example.starter.fishery.api.dto;

import com.example.starter.fishery.domain.TripStatus;

import java.util.List;

/**
 * 配额核销结案结果。
 */
public record SettlementResponse(
        String tripCode,
        String vesselCode,
        TripStatus status,
        int year,
        List<WeightLineView> deductedLines,
        boolean replayed) implements ReplayAware {

    @Override
    public SettlementResponse withReplayed(boolean replayed) {
        return new SettlementResponse(tripCode, vesselCode, status, year, deductedLines, replayed);
    }
}
