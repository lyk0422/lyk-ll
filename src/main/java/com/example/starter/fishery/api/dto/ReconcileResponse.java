package com.example.starter.fishery.api.dto;

import com.example.starter.fishery.domain.TripStatus;

import java.util.List;

/**
 * 航次全量复算结果；各台账与日志链、核销明细均可据此重新核对。
 *
 * @param ledgerConsistent 配额台账 used_kg 是否等于该船该年度核销明细合计
 * @param settledAndClosed 是否满足“已扣减即已结案”
 */
public record ReconcileResponse(
        String tripCode,
        String vesselCode,
        int year,
        TripStatus status,
        boolean frozen,
        List<ReconcileLine> lines,
        List<ReviewCaseView> reviews,
        boolean ledgerConsistent,
        boolean settledAndClosed) {
}
