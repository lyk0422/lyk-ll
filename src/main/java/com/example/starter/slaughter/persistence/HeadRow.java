package com.example.starter.slaughter.persistence;

import com.example.starter.slaughter.domain.InspectionResult;
import com.example.starter.slaughter.domain.InventoryScope;
import com.example.starter.slaughter.domain.JudgmentCategory;

import java.time.Instant;

/**
 * 头只台账行对象。{@code inspectionResult} 为 null 表示漏检。
 */
public record HeadRow(
        Long id,
        String batchNo,
        String headNo,
        InspectionResult inspectionResult,
        Instant inspectedAtUtc,
        JudgmentCategory judgmentCategory,
        Instant judgedAtUtc,
        InventoryScope inventoryScope,
        String limitedRegion,
        Boolean heatTreatment,
        Long receiptId) {
}
