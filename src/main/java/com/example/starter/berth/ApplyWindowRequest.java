package com.example.starter.berth;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 靠泊窗口申请请求。
 *
 * @param requestId          幂等请求编号（必填，全局唯一）
 * @param vesselName         船名
 * @param berthId            泊位编号
 * @param startUtc           预计靠泊开始（UTC，ISO-8601，半开区间起点，含）
 * @param endUtc             预计靠泊结束（UTC，ISO-8601，半开区间终点，不含）
 * @param draftMeters        船舶吃水（米），须大于 0
 * @param pilotsRequired     所需引航员数量，须不小于 0
 * @param replacesRequestId  改期来源请求编号；null 表示非改期申请
 */
public record ApplyWindowRequest(
        @NotBlank String requestId,
        @NotBlank String vesselName,
        @NotBlank String berthId,
        @NotNull Instant startUtc,
        @NotNull Instant endUtc,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal draftMeters,
        @Min(0) int pilotsRequired,
        String replacesRequestId) {
}
