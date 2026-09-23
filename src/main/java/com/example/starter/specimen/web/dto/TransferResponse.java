package com.example.starter.specimen.web.dto;

import com.example.starter.specimen.model.EntityType;

import java.time.Instant;

/**
 * 交接响应：replayed 为 true 表示命中幂等记录，本次未新增交接事件。
 */
public record TransferResponse(
        EntityType entityType,
        String entityId,
        String custodian,
        long version,
        Instant eventAt,
        boolean replayed) {
}
