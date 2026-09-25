package com.example.starter.greenhouse.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 加热/补光/通风指令下发请求。
 * energyKwh 为本指令计量能耗(kWh)，通风类必须为 0；耗能指令在同事务内校验并扣减预算。
 * issuedAt 为指令下发时刻（UTC，ISO-8601）。
 */
public record CommandIssueRequest(
        @NotBlank(message = "不能为空") String zoneCode,
        @NotBlank(message = "不能为空") String commandType,
        @NotNull @DecimalMin("0.000") BigDecimal energyKwh,
        @NotBlank(message = "不能为空") String issuedAt,
        @NotBlank(message = "不能为空") String requestId) {
}
