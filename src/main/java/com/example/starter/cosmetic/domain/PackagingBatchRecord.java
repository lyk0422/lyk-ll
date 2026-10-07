package com.example.starter.cosmetic.domain;

import java.time.Instant;

/**
 * 包装批号登记（持久化对象的内存镜像，字段口径与 packaging_batch 表一致）。
 *
 * <p>首次领用时登记所属产品，登记后不可变更；跨产品借用同一批号拒绝。</p>
 */
public record PackagingBatchRecord(
        String batchNo,
        String productCode,
        Instant createdAtUtc
) {
}
