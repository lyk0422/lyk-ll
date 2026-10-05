package com.example.starter.api;

import com.example.starter.repo.FastTrackDecisionRow;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 快速审批通道结论留痕响应。decision 取值见 fast_track_decision 表注释；时间均为 UTC。
 */
public record FastTrackDecisionResponse(
        Long id,
        long planId,
        LocalDate businessDate,
        String decision,
        String detail,
        Instant decidedAt
) {
    public static FastTrackDecisionResponse of(FastTrackDecisionRow row) {
        return new FastTrackDecisionResponse(row.id(), row.planId(), row.businessDate(),
                row.decision(), row.detail(), row.decidedAt());
    }
}
