package com.example.starter.hazmat.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 接力段打卡请求（交班或接班押运员在接力点打卡）。
 *
 * @param requestId 幂等键
 * @param escortNo  打卡押运员编号（须为该接力段交班或接班押运员）
 * @param checkTime 打卡时刻（Asia/Shanghai），接班时刻不得早于交班时刻
 */
public record RelayCheckinRequest(
        @NotBlank String requestId,
        @NotBlank String escortNo,
        @NotNull LocalDateTime checkTime) {
}
