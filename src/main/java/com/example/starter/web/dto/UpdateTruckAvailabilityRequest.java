package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 发电车可用性变更请求。expectedVersion 须等于车辆当前版本，成功变更加1。
 *
 * @param requestId       幂等请求号
 * @param expectedVersion 期望的车辆版本号
 * @param available       目标可用状态
 */
public record UpdateTruckAvailabilityRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotNull Boolean available) {
}
