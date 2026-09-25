package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 进入覆盖工序请求：仅当 H 点已见证到场才允许。
 */
public record CoverageRequest(
        @NotBlank String requestId,
        @NotBlank String cardNo,
        @NotBlank String nextOperation) {
}
