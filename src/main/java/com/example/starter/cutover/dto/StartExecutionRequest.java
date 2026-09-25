package com.example.starter.cutover.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 进入窗口执行请求。startTime 为执行与首步开始时间（Asia/Shanghai），
 * 也是后续关键验证点 10 分钟超时的起算依据。
 */
public record StartExecutionRequest(
        @NotBlank String requestUid,
        @NotBlank String requestId,
        @NotNull LocalDateTime startTime
) {
}
