package com.example.starter.dye.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 缸次视图（缸差台账复算入口数据）。
 */
public record VatRunResponse(
        Long id,
        String runNo,
        String vatCode,
        String recipeCode,
        Integer recipeVersion,
        Integer liquorRatio,
        BigDecimal fabricKg,
        String mergedRunNo,
        String status,
        int repairCount,
        BigDecimal outDeltaE,
        String finalDisposition,
        LocalDateTime judgedAt,
        LocalDateTime createdAt) {
}
