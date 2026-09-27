package com.example.starter.web.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 发电车到场确认请求。逐车唯一记录；全部到场才视为全负荷恢复并供电。
 *
 * @param requestId       幂等请求号
 * @param orderId         调度单ID
 * @param expectedVersion 调度单期望版本
 * @param vehicleId       到场发电车ID
 * @param arrivedAt       精确 UTC 到场时刻
 */
public record VehicleArrivalRequest(
        @NotBlank String requestId,
        @NotNull Long orderId,
        @NotNull @Positive Integer expectedVersion,
        @NotNull Long vehicleId,
        @NotNull LocalDateTime arrivedAt) {
}
