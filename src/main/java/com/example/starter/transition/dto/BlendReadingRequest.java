package com.example.starter.transition.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 实际掺配读数提交请求：按订单与采样时刻去重。
 *
 * @param requestId       幂等键
 * @param orderNo         改品种单号
 * @param sampledAt       采样时刻，同一订单同时刻只允许一条读数
 * @param actualRatioPct  实际掺配比例读数，单位 %
 */
public record BlendReadingRequest(
        @NotBlank String requestId,
        @NotBlank String orderNo,
        @NotNull LocalDateTime sampledAt,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal actualRatioPct) {
}
