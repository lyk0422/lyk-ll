package com.example.starter.trial.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 访视数据登记请求。
 *
 * @param visitAtUtc   访视发生时刻（UTC；与揭盲当日 UTC 日界比较判定是否排除）
 * @param visitType    访视类型（如 BASELINE / WEEK_2 / FINAL）
 * @param measureValue 测量值（合成数据，可为空）
 */
public record VisitRequest(
        @NotNull Instant visitAtUtc,
        @NotBlank String visitType,
        Double measureValue) {
}
