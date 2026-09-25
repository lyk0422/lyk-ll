package com.example.starter.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 双人清点请求：countedAmount 为两人共同清点确认数；custodianCode 为清点责任人（押运组长），
 * 发生长短款时作为挂账责任人，逾期销账将冻结其新任务。
 */
public record CountRequest(
        @NotBlank String requestId,
        @NotNull @PositiveOrZero Long countedAmount,
        @NotBlank String custodianCode
) {
}
