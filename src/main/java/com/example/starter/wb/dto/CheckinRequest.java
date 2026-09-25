package com.example.starter.wb.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.math.BigDecimal;
import java.util.List;

/**
 * 值机阶段业载更新请求：以全量明细重算舱单。
 */
public record CheckinRequest(
        @NotBlank String requestId,
        @NotEmpty List<LoadItem> items,
        BigDecimal fuelKg,
        @NotBlank String operator
) {
}
