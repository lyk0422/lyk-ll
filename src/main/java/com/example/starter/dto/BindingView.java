package com.example.starter.dto;

import java.time.Instant;

/**
 * 子批绑定台账视图。
 */
public record BindingView(
        String batchNo,
        int declaredQuantity,
        String status,
        Instant boundAt,
        Instant consumedAt,
        Instant releasedAt,
        String releaseReason
) {
}
