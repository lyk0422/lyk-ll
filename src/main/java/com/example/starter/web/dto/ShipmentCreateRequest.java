package com.example.starter.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * 创建货件请求。
 *
 * @param shipmentId                货件唯一标识
 * @param minTempC                  允许温度闭区间下限（摄氏度）
 * @param maxTempC                  允许温度闭区间上限（摄氏度）
 * @param maxExcursionMinutes       最大连续超限分钟数
 * @param maxSampleIntervalMinutes  最大采样间隔分钟数
 * @param custodian                 初始保管方
 * @param startTime                 运输起始时间（Unix 毫秒）；为空时取服务端当前时间
 */
public record ShipmentCreateRequest(
        @NotBlank String shipmentId,
        @NotNull BigDecimal minTempC,
        @NotNull BigDecimal maxTempC,
        @PositiveOrZero int maxExcursionMinutes,
        @PositiveOrZero int maxSampleIntervalMinutes,
        @NotBlank String custodian,
        Long startTime
) {
}
