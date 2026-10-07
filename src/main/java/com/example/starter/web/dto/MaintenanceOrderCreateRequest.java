package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * 检修单登记请求。
 *
 * @param requestId  幂等请求标识
 * @param segmentIds 涉及分段清单，不能为空且须同属本线路
 * @param startAt    封锁窗口起点（UTC，含）
 * @param endAt      封锁窗口终点（UTC，不含）
 */
public record MaintenanceOrderCreateRequest(
        @NotBlank String requestId,
        @NotEmpty List<@NotNull Long> segmentIds,
        @NotNull Instant startAt,
        @NotNull Instant endAt) {
}
