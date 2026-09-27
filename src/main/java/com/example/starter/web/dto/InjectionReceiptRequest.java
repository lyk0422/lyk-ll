package com.example.starter.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 加注执行回执请求：把预占转实际消耗并释放未用部分。
 *
 * @param stationCode     门站编码
 * @param requestId       本次回执的幂等键
 * @param planRequestId   原加注计划下达的 requestId
 * @param actualKg        实际消耗量 kg 三位小数，不得大于预占量
 * @param expectedVersion 储罐期望版本号
 * @param receiptAt       回执时刻（业务时间，Asia/Shanghai）
 */
public record InjectionReceiptRequest(
        @NotBlank String stationCode,
        @NotBlank String requestId,
        @NotBlank String planRequestId,
        @NotNull @DecimalMin("0") BigDecimal actualKg,
        @NotNull @PositiveOrZero Integer expectedVersion,
        @NotNull LocalDateTime receiptAt) {
}
