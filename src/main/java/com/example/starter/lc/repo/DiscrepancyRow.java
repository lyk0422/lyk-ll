package com.example.starter.lc.repo;

import java.time.Instant;

/**
 * 不符点表行对象。
 *
 * @param waived 是否已被申请人声明放弃
 */
public record DiscrepancyRow(
        Long id,
        String batchNo,
        String docType,
        String description,
        boolean waived,
        Instant createdAt) {
}
