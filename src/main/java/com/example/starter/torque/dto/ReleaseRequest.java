package com.example.starter.torque.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 放行归档请求；归档为终态。
 */
public record ReleaseRequest(
        @NotBlank String requestId
) {
}
