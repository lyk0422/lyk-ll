package com.example.rebate.web.dto;

import java.time.Instant;

/**
 * 迟到单证事件视图。
 */
public record LateEventView(
        Long eventId,
        String batchNo,
        String batchStatus,
        String declarationNo,
        String invoiceNo,
        String note,
        Instant recordedAt) {
}
