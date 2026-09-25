package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 环控指令与能耗预算相关请求/响应。加热/补光下发前校验当日（UTC 日界）剩余预算，
 * 能耗计量与预算扣减在同一事务内完成；通风为零能耗指令，预算恰尽后仍允许。
 */
public final class CommandDtos {

    private CommandDtos() {
    }

    public record BudgetRequest(
            @NotBlank @Size(max = 64) String zoneCode,
            @NotNull LocalDate budgetDate,
            @NotNull BigDecimal budgetKwh) {
    }

    public record BudgetResponse(
            String zoneCode,
            LocalDate budgetDate,
            BigDecimal budgetKwh,
            BigDecimal consumedKwh,
            BigDecimal remainingKwh) {
    }

    public record CommandRequest(
            @NotBlank @Size(max = 96) String requestId,
            @NotBlank @Size(max = 64) String zoneCode,
            @NotBlank String commandType,
            BigDecimal energyKwh,
            Instant issuedAt) {
    }

    public record CommandResponse(
            Long commandId,
            String requestId,
            String zoneCode,
            String commandType,
            BigDecimal energyKwh,
            LocalDate budgetDate,
            Instant issuedAt,
            BigDecimal remainingKwh) {
    }
}
