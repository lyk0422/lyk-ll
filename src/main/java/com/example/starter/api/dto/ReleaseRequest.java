package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 放行归档请求：归档为终态，重复归档幂等成功。
 */
public record ReleaseRequest(
        @NotBlank String requestId,
        @NotBlank String cardNo,
        @NotBlank String releasedBy) {
}
