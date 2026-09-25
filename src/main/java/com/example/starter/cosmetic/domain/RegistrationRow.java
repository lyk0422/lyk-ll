package com.example.starter.cosmetic.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 备案记录行；时间均为 UTC，publicityDeadline 为公示期第10日24:00（UTC）。
 */
public record RegistrationRow(
        long id,
        String registrationNo,
        long productId,
        int version,
        ChangeType changeType,
        Long previousId,
        RegistrationStatus status,
        String packagingText,
        LocalDate publicityStartDate,
        Instant publicityDeadline,
        Instant effectiveAt,
        Instant cancelledAt,
        String rejectReason,
        String requestId,
        Instant createdAt) {
}
