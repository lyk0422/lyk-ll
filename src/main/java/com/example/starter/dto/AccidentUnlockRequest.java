package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 事故解除请求，解除须现场确认。
 *
 * @param requestId 幂等键
 * @param confirmBy 现场确认人，必填
 */
public record AccidentUnlockRequest(
        @NotBlank String requestId,
        @NotBlank String confirmBy) {
}
