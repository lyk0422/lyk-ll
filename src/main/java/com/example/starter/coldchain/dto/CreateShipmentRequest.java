package com.example.starter.coldchain.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 建货件请求。
 *
 * @param shipmentId                货件唯一标识
 * @param minTemp                   允许温度闭区间下限（摄氏度，含端点）
 * @param maxTemp                   允许温度闭区间上限（摄氏度，含端点）
 * @param maxExcursionMinutes       允许的最大连续超限分钟数
 * @param maxSampleIntervalSeconds  相邻读数最大采样间隔（秒）
 * @param custodian                 首段保管方
 * @param startTime                 首段开始时间（Asia/Shanghai）
 */
public record CreateShipmentRequest(
        @NotBlank String shipmentId,
        @NotNull Double minTemp,
        @NotNull Double maxTemp,
        @Min(0) int maxExcursionMinutes,
        @Min(1) int maxSampleIntervalSeconds,
        @NotBlank String custodian,
        java.time.LocalDateTime startTime
) {
}
