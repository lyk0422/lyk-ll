package com.example.starter.hazmat.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 人员资格预约请求：为启用人员管控的接力段预约接班员班段。
 *
 * @param requestId     幂等键（指纹覆盖全部字段与精确时刻）
 * @param escortId      接班员编号（受控段 IN 确认必须来自该人员）
 * @param shiftStartUtc 班段起点（UTC），半开区间[start,end)
 * @param shiftEndUtc   班段终点（UTC），半开区间[start,end)；起止有序且被资格区间覆盖
 */
public record ReservationCreateRequest(
        @NotBlank String requestId,
        @NotBlank String escortId,
        @NotNull LocalDateTime shiftStartUtc,
        @NotNull LocalDateTime shiftEndUtc) {
}
