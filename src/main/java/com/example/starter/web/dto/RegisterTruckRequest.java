package com.example.starter.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 发电车登记请求。容量为正整数kW；登记后车辆版本从1开始，默认可用。
 *
 * @param requestId  幂等请求号
 * @param capacityKw 容量，单位kW，正整数
 * @param feederId   服务馈线编号
 */
public record RegisterTruckRequest(
        @NotBlank String requestId,
        @NotNull @Min(1) Integer capacityKw,
        @NotBlank String feederId) {
}
