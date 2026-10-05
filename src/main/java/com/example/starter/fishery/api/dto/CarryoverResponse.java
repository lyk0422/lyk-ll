package com.example.starter.fishery.api.dto;

import java.math.BigDecimal;

/**
 * 年度配额结转让账受理结果。
 */
public record CarryoverResponse(
        String vesselCode,
        String speciesCode,
        int fromYear,
        int toYear,
        BigDecimal amountKg,
        boolean replayed) implements ReplayAware {

    @Override
    public CarryoverResponse withReplayed(boolean replayed) {
        return new CarryoverResponse(vesselCode, speciesCode, fromYear, toYear, amountKg, replayed);
    }
}
