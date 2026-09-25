package com.example.starter.water.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * 计量流量读数上报请求。
 *
 * @param meterPointId 计量点 ID
 * @param readingTime  表底时刻（UTC，ISO-8601，如 2026-01-15T08:00:00Z）
 * @param cumulative   表底累计读数（m³），必须非负
 */
public record ReadingRequest(
        @NotNull Long meterPointId,
        @NotNull String readingTime,
        @NotNull @DecimalMin(value = "0", message = "表底读数不能为负") BigDecimal cumulative) {
}
