package com.example.starter.web.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 发电车组合调度请求。expectedVersion 为工单当前版本；dispatchedAt 为UTC精确下发时刻；
 * 车总容量必须覆盖合计负荷（恰等合法），任一车冲突整单不派。
 *
 * @param requestId       幂等请求号
 * @param workOrderId     未复电工单ID
 * @param expectedVersion 期望的工单版本号
 * @param loads           重要用户负荷清单（冻结）
 * @param truckIds        组合车辆ID列表，不可重复
 * @param dispatchedAt    UTC精确下发时刻
 */
public record CreateGeneratorDispatchRequest(
        @NotBlank String requestId,
        @NotNull Long workOrderId,
        @NotNull Long expectedVersion,
        @NotEmpty List<@Valid LoadItem> loads,
        @NotEmpty List<@NotNull Long> truckIds,
        @NotNull Instant dispatchedAt) {
}
