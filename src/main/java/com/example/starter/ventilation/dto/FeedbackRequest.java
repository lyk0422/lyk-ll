package com.example.starter.ventilation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 设备到位反馈（模拟现场机电系统回报，无需真实外部系统）。
 *
 * @param requestId     反馈幂等键
 * @param commandUid    对应设备指令编码
 * @param deviceCode    反馈设备编码
 * @param inPosition    是否到位：true 到位确认；false 尚未到位
 * @param observedState 现场观测状态（可为空，仅记录）
 */
public record FeedbackRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 48) String commandUid,
        @NotBlank @Size(max = 32) String deviceCode,
        @NotNull Boolean inPosition,
        @Size(max = 16) String observedState) {
}
