package com.example.rebate.web.dto;

import java.time.Instant;

/**
 * 追缴回执视图。
 */
public record ReceiptView(
        long id,
        String receiptSerial,
        long amountCents,
        Instant occurredAt,
        Instant registeredAt) {
}
