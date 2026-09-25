package com.example.starter.marpol.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 核查视图，含逐条结论（钉住版本）与复查记录。
 */
public record InspectionResponse(
        Long id,
        String inspectionNo,
        String shipImo,
        String officer,
        String status,
        LocalDate inspectionDate,
        Instant issuedAt,
        List<ItemView> items,
        List<ReviewView> reviews) {

    /**
     * 单条结论视图。
     */
    public record ItemView(
            Long id,
            Long rootRecordId,
            Long pinnedRecordId,
            Integer pinnedVersionNo,
            String decision,
            String reason,
            String itemStatus,
            LocalDate dueDate,
            Instant rectifiedAt) {
    }

    /**
     * 复查补充证据视图。
     */
    public record ReviewView(Long id, Long itemId, String evidence, Instant createdAt) {
    }
}
