package com.example.starter.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 粮情上报告警请求。
 *
 * @param requestId  幂等键
 * @param measuredAt 采样时刻（Asia/Shanghai 墙钟时间）
 * @param readings   各测点读数，至少一个温度点
 */
public record AlarmRequest(
        @NotBlank String requestId,
        @NotBlank String siloCode,
        @NotNull LocalDateTime measuredAt,
        @NotEmpty @Valid List<@Valid ReadingPoint> readings
) {
}
