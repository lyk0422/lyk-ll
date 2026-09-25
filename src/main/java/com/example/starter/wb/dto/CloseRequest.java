package com.example.starter.wb.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 放行关闭请求。
 */
public record CloseRequest(
        @NotBlank String requestId,
        @NotBlank String closedBy
) {
}
