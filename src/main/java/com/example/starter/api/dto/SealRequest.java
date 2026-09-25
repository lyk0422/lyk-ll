package com.example.starter.api.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 日结封存请求（以 requestId 幂等；封存为终态，重复封存返回首次结果）。
 */
public record SealRequest(
        @NotBlank String requestId,
        @NotBlank String planNo,
        @NotNull LocalDate summaryDate) {
}
