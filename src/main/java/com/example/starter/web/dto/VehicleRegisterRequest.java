package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 发电车登记请求。容量为正整数 kW，发电车仅服务登记馈线。
 *
 * @param requestId  幂等请求号
 * @param code       发电车编号（合成数据，唯一）
 * @param capacityKw 额定容量，正整数，单位kW
 * @param feederId   服务馈线编号
 * @param available  可用状态，空表示可用
 */
public record VehicleRegisterRequest(
        @NotBlank String requestId,
        @NotBlank String code,
        @NotNull @Positive Integer capacityKw,
        @NotBlank String feederId,
        Boolean available) {
}
