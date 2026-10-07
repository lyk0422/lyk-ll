package com.example.starter.transition.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 投料申请视图：总浆量、应投回收量（精确计算）与实际回收合计，含各卷扣量明细。
 */
public record FeedView(
        String feedNo,
        String orderNo,
        int blendVersionNo,
        BigDecimal totalPulpKg,
        BigDecimal requiredRecycleKg,
        BigDecimal actualRecycleKg,
        List<FeedItemView> items,
        LocalDateTime createdAt) {

    /**
     * 单卷扣量明细。
     */
    public record FeedItemView(
            String rollNo,
            BigDecimal kg) {
    }
}
