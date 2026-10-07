package com.example.starter.compliance.service;

import java.util.List;

/**
 * 返工料相关响应模型；时间字段均为 UTC epoch 毫秒，公斤字段为保留原始精度的文本。
 */
public final class ReworkResults {

    private ReworkResults() {
    }

    /**
     * 返工料账视图：可勾稽来源（量产记录、配方版本、冻结过敏原集合）、
     * 原量 availableKg、累计消费 consumedKg 与余额 balanceKg；
     * version 为料账乐观版本，从 1 开始，成功修改/消费加 1。
     */
    public record ReworkMaterialView(
            Long materialId,
            String materialCode,
            Long sourceProductionRecordId,
            String sourceProductCode,
            Integer sourceFormulaVersion,
            List<String> allergens,
            String availableKg,
            String consumedKg,
            String balanceKg,
            Integer version,
            Long registeredAt,
            Long updatedAt,
            boolean replayed) {
    }

    /** 返工料消费流水视图：历史消费不可改。 */
    public record ReworkConsumptionView(
            Long consumptionId,
            Long productionRecordId,
            String weightKg,
            Long consumedAt) {
    }

    /** 某料号全部消费流水（按发生先后升序）。 */
    public record ReworkConsumptionListView(
            String materialCode,
            List<ReworkConsumptionView> consumptions) {
    }
}
