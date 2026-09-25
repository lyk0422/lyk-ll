package com.example.starter.cosmetic.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 备案详情（含宣称、配方、报告与期限口径），可复算。
 */
public record RegistrationDetailResponse(
        long registrationId,
        String registrationNo,
        long productId,
        int version,
        String changeType,
        Long previousId,
        String status,
        String packagingText,
        List<String> claims,
        List<IngredientPayload> ingredients,
        List<ReportPayload> reports,
        LocalDate publicityStartDate,
        Instant publicityDeadline,
        Instant effectiveAt,
        Instant cancelledAt,
        String rejectReason,
        String requestId,
        Instant createdAt) {
}
