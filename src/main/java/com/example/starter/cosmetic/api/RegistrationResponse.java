package com.example.starter.cosmetic.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 备案提交/变更/注销的统一响应；时间均为 UTC。
 */
public record RegistrationResponse(
        long registrationId,
        String registrationNo,
        long productId,
        int version,
        String changeType,
        String status,
        boolean reviewPassed,
        List<ReviewItemDto> reviewItems,
        LocalDate publicityStartDate,
        Instant publicityDeadline,
        Instant effectiveAt,
        Instant cancelledAt,
        String message) {
}
