package com.example.starter.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 绑定日前计划的限电指令受理请求。
 *
 * @param requestId     幂等键，覆盖全部业务字段与精确时刻
 * @param farmCode      风场编码
 * @param targetPowerKw 限电后全场目标出力（kW，正整数）
 * @param planDate      绑定计划的 UTC 日期
 * @param segmentIndex  绑定时段序号 0..95（UTC 半开区间）
 * @param planVersionNo 绑定的已发布计划版本号；必须为该场该日当前版本，过期版本 422
 * @param operateAt     调用方给出的精确受理时刻（UTC，ISO-8601），参与结构化指纹，保证同参重放稳定
 */
public record PlanOrderReceiveRequest(
        @NotBlank String requestId,
        @NotBlank String farmCode,
        @Positive int targetPowerKw,
        @NotNull LocalDate planDate,
        @Min(0) @Max(95) int segmentIndex,
        @Positive int planVersionNo,
        @NotNull Instant operateAt) {
}
