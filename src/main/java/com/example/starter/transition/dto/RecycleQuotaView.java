package com.example.starter.transition.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 回收额度视图：勾稽原额度、当前额度、累计消费与失效原因，含消费凭据明细。
 */
public record RecycleQuotaView(
        String rollNo,
        BigDecimal registeredKg,
        BigDecimal currentKg,
        BigDecimal consumedKg,
        BigDecimal availableKg,
        int version,
        String status,
        String invalidatedReason,
        String requestId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<ConsumptionView> consumptions) {

    /**
     * 单条投料消耗凭据。
     */
    public record ConsumptionView(
            String feedNo,
            String orderNo,
            BigDecimal kg,
            LocalDateTime createdAt) {
    }
}
