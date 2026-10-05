package com.example.starter.deicing.web.dto;

import com.example.starter.deicing.domain.BorrowStatus;
import java.time.Instant;

/**
 * 借用单视图，所有时刻均为 UTC。
 *
 * @param returnedAt 归还登记时刻（UTC），空值表示未归还
 */
public record BorrowOrderView(
        String requestId,
        String tankId,
        String fluidCode,
        int quantity,
        String fromTeam,
        String toTeam,
        BorrowStatus status,
        Instant createdAt,
        Instant returnedAt) {
}
