package com.example.starter.gas.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * 日结请求：核对指定日历日的加臭剂消耗与累计流量勾稽。
 */
public record SettlementRequest(
        @NotBlank String requestId,
        @NotBlank String stationCode,
        @NotNull LocalDate settleDate
) {
}
