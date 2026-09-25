package com.example.starter.cutover.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 窗口巡检/强制收口请求：以指定时间判定窗口是否超期。
 * 窗口结束仍有未完成步骤 -> WINDOW_EXPIRED 并强制回退；恰在窗口终点完成的步骤有效。
 */
public record ExpireCheckRequest(
        @NotBlank String requestUid,
        @NotBlank String requestId,
        @NotNull LocalDateTime atTime
) {
}
