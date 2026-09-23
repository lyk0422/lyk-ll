package com.example.starter.specimen.model;

import java.time.Instant;

/**
 * 保管交接事件行：记录一次保管方变更；requestId 为幂等键，重复交接不新增事件。
 */
public record CustodyEventRow(
        long id,
        EntityType entityType,
        String entityId,
        String fromCustodian,
        String toCustodian,
        Instant eventAt,
        String requestId,
        Instant createdAt) {
}
