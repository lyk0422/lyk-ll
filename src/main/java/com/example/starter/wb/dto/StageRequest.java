package com.example.starter.wb.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 阶段推进请求（装载确认等无业务字段的操作）。
 */
public record StageRequest(
        @NotBlank String requestId,
        @NotBlank String operator
) {
}
