package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * 加注计划取消请求：只释放尚未执行的预占。
 *
 * @param stationCode     门站编码
 * @param requestId       幂等键
 * @param expectedVersion 期望的储罐当前版本号，不一致返回 409
 * @param planRequestId   被取消的加注计划单号
 * @param cancelledAt     取消精确时刻（业务时间，Asia/Shanghai）
 */
public record InjectionCancelRequest(
        @NotBlank String stationCode,
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank String planRequestId,
        @NotNull LocalDateTime cancelledAt) {
}
