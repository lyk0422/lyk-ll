package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 降掺指令执行确认请求；requestId 幂等，指纹覆盖 requestId 与指令 ID。
 */
public record ConfirmCommandRequest(
        @NotBlank String requestId) {
}
