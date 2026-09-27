package com.example.starter.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 补液入账请求：唯一运输批号、数量与合成验收浓度，须两名不同交接人共同确认。
 *
 * @param stationCode               门站编码
 * @param requestId                 幂等键
 * @param transportBatchNo          唯一运输批号
 * @param quantityKg                补液数量 kg 三位小数，加入后不得超容量（恰等允许）
 * @param acceptedConcentrationMgM3 合成验收浓度 mg/m3，须落在许可闭区间
 * @param firstHandler              第一名交接人工号
 * @param secondHandler             第二名交接人工号（须不同）
 * @param expectedVersion           储罐期望版本号
 * @param deliveredAt               交接时刻（业务时间，Asia/Shanghai）
 */
public record ReplenishRequest(
        @NotBlank String stationCode,
        @NotBlank String requestId,
        @NotBlank String transportBatchNo,
        @NotNull @DecimalMin(value = "0.001", message = "补液数量必须为正") BigDecimal quantityKg,
        @NotNull @DecimalMin("0") BigDecimal acceptedConcentrationMgM3,
        @NotBlank String firstHandler,
        @NotBlank String secondHandler,
        @NotNull @PositiveOrZero Integer expectedVersion,
        @NotNull LocalDateTime deliveredAt) {
}
