package com.example.starter.api;

/**
 * 三方对账复算结果：计划数、押运员清点数、设备入账数三方一致才可核销；
 * discrepancyAmount=清点数-计划数，仅在三方不一致时有挂账。
 */
public record ReconciliationResponse(
        Long planId,
        String status,
        Long plannedAmount,
        Long countedAmount,
        Long deviceAmount,
        boolean consistent,
        Long discrepancyAmount,
        Long discrepancyId
) {
}
