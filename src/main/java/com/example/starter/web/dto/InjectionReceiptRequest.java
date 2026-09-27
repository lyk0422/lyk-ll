package com.example.starter.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 加注执行回执请求：预占转实际消耗并释放未用部分，实际量不得大于预占。
 *
 * @param stationCode     门站编码
 * @param requestId       幂等键
 * @param expectedVersion 期望的储罐当前版本号，不一致返回 409
 * @param planRequestId   被回执的加注计划单号
 * @param actualKg        实际消耗 kg（0 ≤ 实际量 ≤ 预占量）
 * @param executedAt      执行精确时刻（业务时间，Asia/Shanghai）
 */
public record InjectionReceiptRequest(
        @NotBlank String stationCode,
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank String planRequestId,
        @NotNull @DecimalMin("0") BigDecimal actualKg,
        @NotNull LocalDateTime executedAt) {
}
