package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;
import java.util.List;

/**
 * 检修单修改请求：expectedVersion 与当前版本不一致返回 409，成功后版本加 1。
 *
 * @param requestId       幂等请求标识
 * @param expectedVersion 期望的当前版本（新建后为 1）
 * @param startAt         新窗口起点（UTC，含）
 * @param endAt           新窗口终点（UTC，不含）
 * @param reason          新检修原因
 * @param segmentIds      新涉及分段清单，不能为空或跨线路
 */
public record MaintenanceOrderModifyRequest(
        @NotBlank String requestId,
        @NotNull @PositiveOrZero Integer expectedVersion,
        @NotNull Instant startAt,
        @NotNull Instant endAt,
        @NotBlank String reason,
        @NotEmpty List<@NotNull Long> segmentIds) {
}
