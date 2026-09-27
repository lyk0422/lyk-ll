package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 发电车登记请求。容量为正整数kW；available 为空时默认可用。
 *
 * @param requestId  幂等请求号
 * @param truckCode  发电车编号（合成数据），全局唯一
 * @param capacityKw 额定容量，单位kW，正整数
 * @param feederId   服务馈线编号
 * @param available  是否可用，空表示 TRUE
 */
public record TruckRegisterRequest(
        @NotBlank String requestId,
        @NotBlank String truckCode,
        @NotNull @Positive Integer capacityKw,
        @NotBlank String feederId,
        Boolean available) {
}
