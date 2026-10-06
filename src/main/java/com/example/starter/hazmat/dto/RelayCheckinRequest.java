package com.example.starter.hazmat.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 接力双人打卡请求。
 *
 * @param requestId 幂等键
 * @param escortId  押运员编号（交接班双方须不同，单人打卡不生效）
 * @param role      打卡角色：OUT 交班 / IN 接班
 * @param checkTime 打卡时刻（Asia/Shanghai）；接班时刻须严格晚于交班时刻
 */
public record RelayCheckinRequest(
        @NotBlank String requestId,
        @NotBlank String escortId,
        @NotBlank String role,
        @NotNull LocalDateTime checkTime) {
}
