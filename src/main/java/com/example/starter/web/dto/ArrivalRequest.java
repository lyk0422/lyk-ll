package com.example.starter.web.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 发电车到场确认请求。expectedVersion 为调度单当前版本；arrivedAt 为UTC精确到场时刻。
 *
 * @param requestId       幂等请求号
 * @param expectedVersion 期望的调度单版本号
 * @param truckId         到场车辆ID
 * @param arrivedAt       UTC精确到场时刻
 */
public record ArrivalRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotNull Long truckId,
        @NotNull Instant arrivedAt) {
}
