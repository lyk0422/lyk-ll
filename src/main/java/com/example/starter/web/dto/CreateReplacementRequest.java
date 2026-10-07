package com.example.starter.web.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 热备接替创建请求。仅SUPPLYING调度单可申请；expectedVersion 为原调度单当前版本；
 * 引用一辆正在供电的旧车与1至3辆未占用新车，新车同馈线且容量之和不少于旧车容量（恰等合法）。
 *
 * @param requestId       幂等请求号
 * @param dispatchId      原SUPPLYING调度单ID
 * @param expectedVersion 期望的原调度单版本号
 * @param oldTruckId      被接替的供电旧车ID
 * @param newTruckIds     热备新车ID列表，1至3辆，不可重复
 * @param createdAt       UTC精确申请时刻
 */
public record CreateReplacementRequest(
        @NotBlank String requestId,
        @NotNull Long dispatchId,
        @NotNull Long expectedVersion,
        @NotNull Long oldTruckId,
        @NotEmpty @Size(max = 3) List<@NotNull Long> newTruckIds,
        @NotNull Instant createdAt) {
}
