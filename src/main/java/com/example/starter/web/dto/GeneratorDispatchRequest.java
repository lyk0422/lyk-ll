package com.example.starter.web.dto;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 发电车组合调度请求。版本从 1 开始；新单下发 expectedVersion 传 1。
 *
 * @param requestId      幂等请求号
 * @param workOrderId    引用的未复电工单ID
 * @param expectedVersion 期望工单/调度版本，从1开始；本服务新单恒为 1
 * @param vehicleIds     组合发电车ID集合（非空、不重复）
 * @param loads          重要用户保安负荷清单（非空）
 * @param dispatchedAt   精确 UTC 下发时刻
 */
public record GeneratorDispatchRequest(
        @NotBlank String requestId,
        @NotNull Long workOrderId,
        @NotNull @Positive Integer expectedVersion,
        @NotEmpty List<@NotNull Long> vehicleIds,
        @NotEmpty @Valid List<LoadItemRequest> loads,
        @NotNull LocalDateTime dispatchedAt) {
}
