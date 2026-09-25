package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 公示异议请求：以 requestId 幂等，公示期内受理后转复核。
 *
 * @param requestId 幂等请求ID
 * @param reason    异议理由
 */
public record ObjectionRequest(
        @NotBlank String requestId,
        @NotBlank String reason) {
}
