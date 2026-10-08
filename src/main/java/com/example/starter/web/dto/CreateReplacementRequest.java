package com.example.starter.web.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 热备接替申请请求（调度单ID取自请求路径）。仅 SUPPLYING 调度单可申请：
 * 引用一辆正在供电旧车与1至3辆未占用新车，新车须与旧车同馈线且容量之和不少于旧车容量（恰等合法）；
 * 创建时全量预占新车，旧车继续供电。
 *
 * @param requestId       幂等请求号
 * @param expectedVersion 期望的调度单当前版本号（不符409）
 * @param oldTruckId      被换下的正在供电旧车ID
 * @param newTruckIds     热备新车ID列表，1至3辆，不可重复
 * @param createdAt       UTC精确申请时刻
 */
public record CreateReplacementRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotNull Long oldTruckId,
        @NotNull @Size(min = 1, max = 3) List<@NotNull Long> newTruckIds,
        @NotNull Instant createdAt) {
}
