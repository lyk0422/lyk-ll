package com.example.starter.pump.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 闸门合成到位回执上送：回执按“序列＋站点”唯一，只归属所报候选序列。
 *
 * @param requestId   幂等键
 * @param sequenceId  候选强排序列ID
 * @param stationCode 泵站代码
 * @param opening     实际到位闸门开度（百分比，0-100），须与候选固化目标一致才算到位
 * @param receiptTime 回执精确UTC时刻
 */
public record GateReceiptRequest(
        @NotBlank String requestId,
        @NotNull Long sequenceId,
        @NotBlank String stationCode,
        @NotNull @DecimalMin(value = "0.0", inclusive = true)
        @DecimalMax(value = "100.0", inclusive = true) BigDecimal opening,
        @NotNull OffsetDateTime receiptTime
) {
}
