package com.example.starter.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通风后效果复测请求。
 *
 * @param requestId  幂等键
 * @param siloCode   粮仓编号
 * @param measuredAt 复测采样时刻
 * @param readings   各测点读数，至少一个温度点
 */
public record RetestRequest(
        @NotBlank String requestId,
        @NotBlank String siloCode,
        @NotNull LocalDateTime measuredAt,
        @NotEmpty @Valid List<@Valid ReadingPoint> readings
) {
}
