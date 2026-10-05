package com.example.starter.hazmat.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 接力段押运员打卡请求。
 *
 * @param requestId 幂等键
 * @param escortId  押运员编号（合成数据）
 * @param role      押运角色：OUTGOING 交班 / INCOMING 接班
 * @param checkTime 打卡时刻（Asia/Shanghai）
 */
public record RelayCheckinRequest(
        @NotBlank String requestId,
        @NotBlank String escortId,
        @NotBlank String role,
        @NotNull LocalDateTime checkTime) {
}
