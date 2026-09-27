package com.example.starter.dto;

import java.time.Instant;

/**
 * 包装子批绑定视图（余量台账明细行）。
 */
public record CustomsBindingView(
        String batchNo,
        int declaredDoses,
        String status,
        String releaseReason,
        Instant createdAt,
        Instant consumedAt,
        Instant releasedAt
) {
}
