package com.example.starter.wbb.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 业载明细行。重量 kg，力臂 m；预配/值机为全量行，LMC 为增量行（件数、重量可负）。
 */
public record PayloadItemDto(
        @NotBlank String category,
        @NotBlank String zoneCode,
        @NotNull Integer pieceCount,
        @NotNull BigDecimal weightKg,
        @NotNull BigDecimal arm
) {
}
