package com.example.starter.slaughter.persistence;

import com.example.starter.slaughter.domain.ChainStatus;

import java.time.Instant;

/**
 * 同步检验链行对象；同批次唯一约束保证同时刻仅一条链。
 */
public record ChainRow(
        Long id,
        String batchNo,
        ChainStatus status,
        int inspectedCount,
        int expectedCount,
        Instant openedAtUtc,
        Instant completedAtUtc) {
}
