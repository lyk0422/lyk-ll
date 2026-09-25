package com.example.starter.ventilation.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 设备到位反馈请求（模拟机电系统回调，无需真实外部系统）。
 *
 * @param requestId  幂等键，同一反馈重放返回首次结果
 * @param sessionId  目标会话 id
 * @param deviceCode 到位设备编码
 * @param result     反馈结果，仅接受 IN_POSITION
 */
public record FeedbackRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull Long sessionId,
        @NotBlank @Size(max = 32) String deviceCode,
        @NotBlank String result
) {
}
