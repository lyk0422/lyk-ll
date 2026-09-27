package com.example.starter.runway.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.List;

/**
 * 例行巡检计划申请发布请求。requestId 为幂等标识，指纹覆盖全部业务字段（含集合与精确时刻）。
 *
 * @param requestId         幂等请求标识
 * @param runwayId          跑道编号（合成数据）
 * @param windowStart       例行窗口起点（UTC，半开窗口，含起点）
 * @param windowEnd         例行窗口终点（UTC，半开窗口，不含终点）
 * @param inspectionMinutes 预计巡检时长（分钟），自窗口起点起须完整落在窗口内
 * @param flightWindows     合成航班占用窗列表（UTC 半开区间）
 */
public record PlanPublishRequest(
        @NotBlank String requestId,
        @NotBlank String runwayId,
        @NotNull Instant windowStart,
        @NotNull Instant windowEnd,
        @Positive int inspectionMinutes,
        @NotNull List<@Valid FlightWindow> flightWindows) {
}
