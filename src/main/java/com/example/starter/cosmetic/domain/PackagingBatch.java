package com.example.starter.cosmetic.domain;

import java.time.Instant;

/**
 * 合成包装批号登记（持久化对象的内存镜像，字段口径与 packaging_batch 表一致）。
 *
 * <p>首次领用时登记所属产品，之后仅允许同产品印次领用；跨产品借用拒绝。</p>
 */
public record PackagingBatch(
        String batchNo,
        String productCode,
        Instant createdAtUtc
) {
}
