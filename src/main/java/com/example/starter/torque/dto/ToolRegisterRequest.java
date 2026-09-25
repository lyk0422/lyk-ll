package com.example.starter.torque.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * 力矩工具台账登记/日校验更新请求。
 *
 * @param lastCheckUtcDate 最近一次日校验的 UTC 日期；工具当日（UTC 日界）有效
 */
public record ToolRegisterRequest(
        @NotBlank String toolNo,
        @NotNull LocalDate lastCheckUtcDate
) {
}
