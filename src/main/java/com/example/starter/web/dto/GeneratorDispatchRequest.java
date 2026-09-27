package com.example.starter.web.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 发电车组合调度请求。expectedVersion 为期望的工单版本；truckIds 为组合车辆，总容量须覆盖合计负荷。
 *
 * @param requestId       幂等请求号
 * @param workOrderId     未复电工单ID
 * @param expectedVersion 期望的工单版本
 * @param truckIds        组合发电车ID列表（去重后总容量覆盖合计负荷，恰等合法）
 */
public record GeneratorDispatchRequest(
        @NotBlank String requestId,
        @NotNull Long workOrderId,
        @NotNull Long expectedVersion,
        @NotEmpty List<@NotNull Long> truckIds) {
}
