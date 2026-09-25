package com.example.starter.feed.domain;

import java.time.Instant;

/**
 * 养殖批次。
 *
 * @param batchNo       批次号
 * @param species       动物种类
 * @param status        ACTIVE / SLAUGHTERED
 * @param createdAt     建档时刻（UTC）
 * @param slaughteredAt 出栏时刻（UTC）；未出栏为 null
 */
public record FarmBatch(
        String batchNo,
        String species,
        BatchStatus status,
        Instant createdAt,
        Instant slaughteredAt) {
}
