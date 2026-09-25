package com.example.starter.cems.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 在线监测读数上报请求。periodType=HOUR 时按小时均值参与连续超标判定；
 * periodType=DAY 时按日均值与日均许可限值比较生成超标标记。
 *
 * @param requestId 幂等键
 * @param outfallCode 排口编码
 * @param periodType 时段类型 HOUR/DAY
 * @param periodStart 时段起始时刻（整点/零点）
 * @param avgValue 时段平均浓度 mg/L
 */
public record ReadingRequest(
        @NotBlank String requestId,
        @NotBlank String outfallCode,
        @NotBlank String periodType,
        @NotNull LocalDateTime periodStart,
        @NotNull @PositiveOrZero BigDecimal avgValue) {
}
