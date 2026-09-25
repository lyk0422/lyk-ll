package com.example.starter.marpol.web;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 排放记录（版本）视图。
 */
public record RecordResponse(
        Long id,
        String requestId,
        Long rootId,
        Integer versionNo,
        Long supersedesId,
        String shipImo,
        String voyageNo,
        String type,
        double longitude,
        double latitude,
        Instant eventTime,
        BigDecimal quantity,
        String unit,
        String status,
        Instant createdAt) {
}
