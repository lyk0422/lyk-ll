package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * 力矩工具登记（合成数据）：calibrationDate 为 UTC 日校验日期，仅当日有效。
 */
public record ToolRegisterRequest(
        @NotBlank String toolCode,
        @NotNull LocalDate calibrationDate) {
}
