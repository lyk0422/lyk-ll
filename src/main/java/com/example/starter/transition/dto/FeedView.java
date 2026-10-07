package com.example.starter.transition.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 投料申请单视图：总浆量、目标比例快照、应投与实际回收量及明细。
 */
public record FeedView(
        String feedNo,
        String orderNo,
        int versionNo,
        BigDecimal totalPulpKg,
        BigDecimal targetRatioPct,
        BigDecimal requiredKg,
        BigDecimal actualKg,
        List<FeedItemView> items,
        String requestId,
        LocalDateTime createdAt) {

    /**
     * 投料明细项。
     */
    public record FeedItemView(
            String rollNo,
            BigDecimal weightKg) {
    }
}
