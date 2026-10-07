package com.example.starter.transition.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 回收额度视图：勾稽原额度、当前额度、累计消费、可用额度与失效原因，含消费凭据。
 */
public record RecycleQuotaView(
        String rollNo,
        BigDecimal initialKg,
        BigDecimal registeredKg,
        BigDecimal consumedKg,
        BigDecimal availableKg,
        BigDecimal invalidatedKg,
        int version,
        String status,
        String invalidReason,
        String requestId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime invalidatedAt,
        List<ConsumptionView> consumptions) {

    /**
     * 单条消费凭据（投料明细），卷改判正品后仍保留可查。
     */
    public record ConsumptionView(
            String feedNo,
            String orderNo,
            BigDecimal weightKg,
            LocalDateTime createdAt) {
    }
}
