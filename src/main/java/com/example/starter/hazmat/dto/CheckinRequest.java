package com.example.starter.hazmat.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 押运打卡请求。
 *
 * @param requestId 幂等键
 * @param waypointSeq 途经点序号（从 0 开始，按路线模板）
 * @param checkTime  实际打卡时刻（Asia/Shanghai）
 */
public record CheckinRequest(
        @NotBlank String requestId,
        @PositiveOrZero int waypointSeq,
        @NotNull LocalDateTime checkTime) {
}
