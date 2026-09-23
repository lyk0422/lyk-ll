package com.example.starter.specimen.web.dto;

import com.example.starter.specimen.model.EntityType;

import java.time.Instant;

/**
 * 保管交接事件响应。
 */
public record CustodyEventResponse(
        long id,
        EntityType entityType,
        String entityId,
        String fromCustodian,
        String toCustodian,
        Instant eventAt,
        String requestId) {
}
