package com.example.starter.trace.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 来源召回详情响应。时间字段均为 UTC；
 * 关联清单同时展示首次冻结（INITIAL）与后来追加（LATER）的隔离关联及其当前状态。
 */
public record RecallResponse(
        Long id,
        Long apiaryId,
        String apiaryCode,
        String apiaryName,
        String flowerPeriod,
        String reason,
        String status,
        Integer version,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime withdrawnAt,
        List<HarvestLinkView> harvestLinks,
        List<ProcessingLinkView> processingLinks) {

    /**
     * 召回-采收批隔离关联视图。
     */
    public record HarvestLinkView(Long harvestBatchId, String batchNo, String origin,
                                  String status, LocalDateTime createdAt) {
    }

    /**
     * 召回-加工批隔离关联视图：仅隔离标记，原加工证据保留。
     */
    public record ProcessingLinkView(Long processingBatchId, String batchNo, String origin,
                                     String status, LocalDateTime createdAt) {
    }
}
