package com.example.starter.waterlogging.api;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 失联判定检查：自该站点最后一条液位数据起，恰满 30 分钟无数据即判定失联并派单。
 *
 * @param requestId  幂等键
 * @param stationCode 被检查站点
 * @param checkTime  判定时刻
 */
public record LossContactCheckRequest(
        @NotBlank String requestId,
        @NotBlank String stationCode,
        @NotNull LocalDateTime checkTime) {
}
