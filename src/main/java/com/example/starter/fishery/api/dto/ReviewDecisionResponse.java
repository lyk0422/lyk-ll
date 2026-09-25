package com.example.starter.fishery.api.dto;

import com.example.starter.fishery.domain.TripStatus;

/**
 * 人工复核结论受理结果；若该船已无其他未决复核，则同事务解除冻结、核销并结案。
 */
public record ReviewDecisionResponse(
        Long reviewId,
        String tripCode,
        ReviewCaseView reviewCase,
        boolean frozen,
        long openReviews,
        boolean settled,
        SettlementResponse settlement,
        TripStatus tripStatus,
        boolean replayed) implements ReplayAware {

    @Override
    public ReviewDecisionResponse withReplayed(boolean replayed) {
        return new ReviewDecisionResponse(reviewId, tripCode, reviewCase, frozen, openReviews,
                settled, settlement, tripStatus, replayed);
    }
}
