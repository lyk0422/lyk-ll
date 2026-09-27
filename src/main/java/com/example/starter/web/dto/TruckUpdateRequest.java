package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 发电车变更请求：携带期望版本，字段为空表示不修改。
 *
 * @param requestId      幂等请求号
 * @param expectedVersion 期望的车辆版本
 * @param capacityKw     新额定容量，单位kW，正整数；空表示不变
 * @param available      新可用状态；空表示不变
 */
public record TruckUpdateRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @Positive Integer capacityKw,
        Boolean available) {
}
