package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * 检修封锁单登记请求（检修版本自 1 起）。
 *
 * @param requestId  幂等请求标识
 * @param startAt    窗口起点（UTC，含）
 * @param endAt      窗口终点（UTC，不含），须晚于起点
 * @param reason     检修原因
 * @param segmentIds 涉及分段清单，不能为空或跨线路
 */
public record MaintenanceOrderRequest(
        @NotBlank String requestId,
        @NotNull Instant startAt,
        @NotNull Instant endAt,
        @NotBlank String reason,
        @NotEmpty List<@NotNull Long> segmentIds) {
}
