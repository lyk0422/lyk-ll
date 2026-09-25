package com.example.starter.fishery.api.dto;

import com.example.starter.fishery.domain.TripStatus;

import java.time.Instant;
import java.util.List;

/**
 * 出港申报受理结果。
 */
public record DepartureResponse(
        String tripCode,
        String vesselCode,
        TripStatus status,
        Instant departAt,
        int year,
        List<WeightLineView> declaredLines,
        boolean replayed) implements ReplayAware {

    @Override
    public DepartureResponse withReplayed(boolean replayed) {
        return new DepartureResponse(tripCode, vesselCode, status, departAt, year, declaredLines, replayed);
    }
}
