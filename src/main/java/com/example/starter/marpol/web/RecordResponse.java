package com.example.starter.marpol.web;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 排放记录单个版本的响应视图。
 */
public record RecordResponse(
        String recordNo,
        Integer version,
        String requestId,
        String shipId,
        String voyageNo,
        String dischargeType,
        BigDecimal latitude,
        BigDecimal longitude,
        Instant eventTime,
        BigDecimal quantity,
        String unit,
        boolean compliant,
        String complianceDetail,
        String status,
        String supersedesRecordNo,
        Integer supersedesVersion,
        Instant createdAt,
        boolean idempotentReplay) {
}
