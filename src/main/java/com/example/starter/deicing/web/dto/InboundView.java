package com.example.starter.deicing.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 补料入库批次视图。
 */
public record InboundView(
        String batchNo,
        String fluidCode,
        BigDecimal quantityLiters,
        Instant expiryTime,
        Instant createdAt) {
}
