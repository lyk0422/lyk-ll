package com.example.starter.web.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 发电车到场确认请求。arrivedAt 为精确到场时刻，不可晚于当前时间。
 *
 * @param requestId       幂等请求号
 * @param expectedVersion 期望的调度单版本
 * @param truckId         到场发电车ID（须在调度方案内）
 * @param arrivedAt       精确到场时刻
 */
public record TruckArrivalRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotNull Long truckId,
        @NotNull LocalDateTime arrivedAt) {
}
