package com.example.starter.pension.dto;

import java.time.LocalDate;

import com.example.starter.pension.domain.ContributionSource;

/**
 * 缴费登记响应。
 */
public record ContributionResponse(
        Long contributionId,
        String memberNo,
        LocalDate contributionDate,
        ContributionSource source,
        long amountCents,
        String usedByPlanNo) {
}
