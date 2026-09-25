package com.example.starter.persistence;

import java.time.Instant;

import com.example.starter.domain.CardStatus;

/**
 * 工序卡行对象。
 *
 * @param dispatchedAt  派工时间(UTC)
 * @param quarantinedAt 整批隔离评审时间(UTC)，null=未隔离
 * @param archivedAt    放行归档时间(UTC)，null=未归档
 */
public record CardRow(
        long id,
        String cardNo,
        String station,
        String productSn,
        String witnessPoint,
        CardStatus status,
        String operator,
        Instant dispatchedAt,
        Instant quarantinedAt,
        Instant archivedAt) {
}
