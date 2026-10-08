package com.example.starter.trace.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 来源召回详情响应。同时展示创建时的首次冻结清单与后来登记批次的追加关联，
 * 以及被加召回隔离标记的加工批（原加工证据保留）。
 * 时间字段均为 UTC。
 */
public record RecallResponse(
        Long id,
        Long apiaryId,
        String apiaryCode,
        String flowerPeriod,
        String reason,
        String status,
        Integer version,
        LocalDateTime createdAt,
        LocalDateTime withdrawnAt,
        List<LinkView> initialFrozenLinks,
        List<LinkView> appendedLinks,
        List<ProcessingMarkView> processingMarks) {

    /**
     * 召回隔离关联视图。
     *
     * @param status ACTIVE 隔离中 / RELEASED 已随召回撤回失效
     */
    public record LinkView(Long linkId, Long harvestBatchId, String batchNo,
                           String status, LocalDateTime createdAt) {
    }

    /**
     * 加工批召回隔离标记视图。
     *
     * @param status ACTIVE 隔离标记中 / RELEASED 已随召回撤回失效
     */
    public record ProcessingMarkView(Long processingBatchId, String processingBatchNo,
                                     String status, LocalDateTime createdAt) {
    }
}
