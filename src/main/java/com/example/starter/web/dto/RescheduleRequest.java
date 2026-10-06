package com.example.starter.web.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 改期请求：调整潮水窗口，须重新校验引航员工时与拖轮占用并重新锁定。
 *
 * @param requestId 幂等键
 * @param tideStart 新潮水窗口开始（含）
 * @param tideEnd   新潮水窗口结束（不含）
 */
public record RescheduleRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull LocalDateTime tideStart,
        @NotNull LocalDateTime tideEnd) {
}
