package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 降掺指令下发/执行确认请求。下发与确认均以 requestId 幂等。
 */
public record CommandActionRequest(
        @NotBlank String requestId,
        @NotBlank String planNo,
        String hourStart) {
}
