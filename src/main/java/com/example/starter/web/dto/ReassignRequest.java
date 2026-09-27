package com.example.starter.web.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 整体改派请求。expectedVersion 为超时调度单当前版本；已到场车保留在新方案，
 * 其余释放并换为 newTruckIds；全方案原子切换。newTruckIds 为空表示仅保留已到场车。
 *
 * @param requestId       幂等请求号
 * @param expectedVersion 期望的旧调度单版本号
 * @param newTruckIds     新增车辆ID列表，可为空
 * @param dispatchedAt    新方案UTC精确下发时刻
 */
public record ReassignRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotNull List<@NotNull Long> newTruckIds,
        @NotNull Instant dispatchedAt) {
}
