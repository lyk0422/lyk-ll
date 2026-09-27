package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalDateTime;

/**
 * 加注计划取消请求：仅释放尚未执行的预占。
 *
 * @param stationCode     门站编码
 * @param requestId       本次取消的幂等键
 * @param planRequestId   原加注计划下达的 requestId
 * @param expectedVersion 储罐期望版本号
 * @param cancelledAt     取消时刻（业务时间，Asia/Shanghai）
 */
public record InjectionCancelRequest(
        @NotBlank String stationCode,
        @NotBlank String requestId,
        @NotBlank String planRequestId,
        @NotNull @PositiveOrZero Integer expectedVersion,
        @NotNull LocalDateTime cancelledAt) {
}
