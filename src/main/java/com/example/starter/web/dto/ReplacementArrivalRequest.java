package com.example.starter.web.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 热备新车到场登记请求。expectedVersion 为接替单当前版本；arrivedAt 为UTC精确到场时刻。
 *
 * @param requestId       幂等请求号
 * @param expectedVersion 期望的接替单版本号
 * @param truckId         到场的热备新车ID
 * @param arrivedAt       UTC精确到场时刻
 */
public record ReplacementArrivalRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotNull Long truckId,
        @NotNull Instant arrivedAt) {
}
