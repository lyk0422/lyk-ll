package com.example.starter.evidence.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 法律保全自动解除请求。
 *
 * @param requestId       请求幂等键
 * @param expectedVersion 预期保全单版本，必须与当前版本一致
 */
public record ReleaseHoldRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion) {
}
