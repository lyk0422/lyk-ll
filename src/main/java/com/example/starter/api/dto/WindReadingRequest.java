package com.example.starter.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 风速数据上报请求。
 *
 * @param requestId 幂等键
 * @param segmentId 分段 id
 * @param measuredAt 测量时刻（秒级，去重键之一）
 * @param speed 风速，单位 m/s
 */
public record WindReadingRequest(
        @NotBlank String requestId,
        @NotNull Long segmentId,
        @NotNull LocalDateTime measuredAt,
        @NotNull @DecimalMin(value = "0", message = "风速不能为负") BigDecimal speed) {
}
