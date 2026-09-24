package com.example.starter.ili.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * 单个检测回合载荷。
 *
 * @param runId       回合标识
 * @param inspectedAt 检测日期
 * @param readings    缺陷读数清单（可为空列表）
 */
public record RunPayload(
        @NotBlank String runId,
        @NotNull LocalDate inspectedAt,
        @NotNull List<@Valid ReadingPayload> readings) {
}
