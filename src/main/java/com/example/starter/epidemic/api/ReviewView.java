package com.example.starter.epidemic.api;

import java.time.OffsetDateTime;

/**
 * 审核轨迹条目。病种变更重起审核时旧条目标记 voided=true 作废留痕。
 */
public record ReviewView(
        int versionNo,
        String stage,
        String reviewer,
        String outcome,
        boolean voided,
        String opinion,
        OffsetDateTime createdAt
) {
}
