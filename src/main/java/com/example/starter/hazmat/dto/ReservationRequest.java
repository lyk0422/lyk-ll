package com.example.starter.hazmat.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 人员资格预约请求：为启用人员管控的接力段预约接班员。
 *
 * @param requestId  幂等键（指纹含全部字段与精确时刻；失败不占键）
 * @param relaySeq   接力点途经点序号（接力段须属于本运单，跨运单接力点拒绝）
 * @param escortId   接班员编号
 * @param shiftStart 班段起点（UTC，半开区间 [start,end)）
 * @param shiftEnd   班段终点（UTC，半开区间 [start,end)），须严格晚于起点
 */
public record ReservationRequest(
        @NotBlank String requestId,
        @PositiveOrZero int relaySeq,
        @NotBlank String escortId,
        @NotNull LocalDateTime shiftStart,
        @NotNull LocalDateTime shiftEnd) {
}
